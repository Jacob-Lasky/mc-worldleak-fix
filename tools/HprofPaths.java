// Memory-lean HPROF analyser: shortest STRONG path from GC roots to every instance of a target class.
// Usage: java -Xmx<heap> [-Dcap=<objects>] [-Dgroup=false] HprofPaths.java <dump.hprof> <target class name>
//            [excludeEdge class#field ...]
// Strong only: java.lang.ref.Reference.referent is never followed (weak/soft/phantom/final refs ignored).
// Roots: every HPROF ROOT_* record plus every class object. Treating every class as a root is right for a
// LIVE dump of a 1.12 client (mod classes are never unloaded); in an -all dump an unloadable class would
// wrongly appear to retain its statics.
// Primitive arrays are left out of the graph (they hold no references). Memory: ~8 B/object id,
// 4 B/object CSR offset, 4 B/object BFS parent, 4 B/object queue, 4 B/edge. -Dcap is the initial id-array
// size; it grows by half when exceeded (a transient copy), so set it above the object count to avoid that.
// Output: instances grouped by route (same fields and classes) with counts; -Dgroup=false prints every path.
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

public class HprofPaths {
    static int idSize;
    static final Map<Long, String> strings = new HashMap<>();
    static final Map<Long, Long> classNameId = new HashMap<>(); // class obj id -> name string id
    static final Map<Long, ClassInfo> classes = new HashMap<>();
    static final List<Long> roots = new ArrayList<>();
    static final Map<Long, String> rootKind = new HashMap<>();
    static Set<String> excluded = new HashSet<>(); // "pkg.Class#field"

    static final class ClassInfo {
        long id, superId, loaderId, signersId, protectionDomainId;
        long[] cpRefVals = new long[0];
        String name;
        long[] fieldNames; byte[] fieldTypes;   // declared instance fields, in dump order
        long[] staticRefNames; long[] staticRefVals;
        int[] refOffsets; String[] refLabels; int dataSize = -1; // flattened (own then supers)
    }

    // ---------------- buffered reader over a FileChannel ----------------
    static final class In {
        // -Dhprof.buffer overrides the 64 MiB read buffer; only useful to test the large-payload path.
        final FileChannel ch; final ByteBuffer buf = ByteBuffer.allocateDirect(Integer.getInteger("hprof.buffer", 64 << 20));
        long base = 0; // file pos of buf[0]
        In(Path p) throws IOException { ch = FileChannel.open(p, StandardOpenOption.READ); buf.limit(0); }
        long pos() { return base + buf.position(); }
        // need(n) serves fixed-size reads and instance payloads that fit the buffer; payload() below
        // reads larger ones (deep class hierarchies can exceed 64 MiB of inherited fields) separately.
        void need(int n) throws IOException {
            if (buf.remaining() >= n) return;
            if (n > buf.capacity()) throw new IOException("record payload " + n + " B exceeds the " + buf.capacity() + " B read buffer");
            base += buf.position(); buf.compact(); while (buf.position() < n) { if (ch.read(buf) < 0) throw new EOFException(); } buf.flip();
        }
        int u1() throws IOException { need(1); return buf.get() & 0xff; }
        int u2() throws IOException { need(2); return buf.getShort() & 0xffff; }
        int u4() throws IOException { need(4); return buf.getInt(); }
        long id() throws IOException { need(idSize); return idSize == 8 ? buf.getLong() : (buf.getInt() & 0xffffffffL); }
        void skip(long n) throws IOException {
            if (n <= buf.remaining()) { buf.position(buf.position() + (int) n); return; }
            long target = pos() + n; base = target; ch.position(target); buf.clear(); buf.limit(0);
        }
        /** An instance payload as a big-endian buffer indexed from 0; the stream is advanced past it. */
        ByteBuffer payload(int len) throws IOException {
            if (len <= buf.capacity()) {
                need(len);
                ByteBuffer b = buf.slice(buf.position(), len);
                buf.position(buf.position() + len);
                return b;
            }
            return ByteBuffer.wrap(bytes(len));
        }
        long ref(ByteBuffer b, int off) { return idSize == 8 ? b.getLong(off) : (b.getInt(off) & 0xffffffffL); }
        byte[] bytes(int n) throws IOException { byte[] b = new byte[n]; int off = 0; while (off < n) { need(1); int k = Math.min(n - off, buf.remaining()); buf.get(b, off, k); off += k; } return b; }
    }

    static int typeSize(int t) {
        switch (t) { case 2: return idSize; case 4: case 8: return 1; case 5: case 9: return 2; case 6: case 10: return 4; case 7: case 11: return 8; }
        throw new IllegalStateException("type " + t);
    }

    interface Visitor {
        default void instance(long id, long classId, In in, int len) throws IOException { in.skip(len); }
        default void objArray(long id, long arrClassId, In in, int count) throws IOException { in.skip((long) count * idSize); }
        default void classDump(ClassInfo c) {}
    }

    static String header(In in) throws IOException {
        StringBuilder sb = new StringBuilder(); int c; while ((c = in.u1()) != 0) sb.append((char) c);
        idSize = in.u4(); in.u4(); in.u4(); return sb.toString();
    }

    // full walk; firstPass=true collects strings/classes/roots
    static void walk(Path p, boolean firstPass, Visitor v) throws IOException {
        In in = new In(p); header(in);
        long size = Files.size(p); long nextReport = 0;
        while (in.pos() < size) {
            int tag = in.u1(); in.u4(); long len = in.u4() & 0xffffffffL;
            if (in.pos() > nextReport) { System.err.printf("  %.0f%%%n", 100.0 * in.pos() / size); nextReport += size / 10; }
            if (tag == 0x01 && firstPass) { long sid = in.id(); strings.put(sid, new String(in.bytes((int) (len - idSize)), "UTF-8")); }
            else if (tag == 0x02 && firstPass) { in.u4(); long cid = in.id(); in.u4(); classNameId.put(cid, in.id()); }
            else if (tag == 0x0C || tag == 0x1C) heap(in, in.pos() + len, firstPass, v);
            else in.skip(len);
        }
    }

    static void heap(In in, long end, boolean firstPass, Visitor v) throws IOException {
        while (in.pos() < end) {
            int st = in.u1();
            switch (st) {
                case 0xFF: { long id = in.id(); if (firstPass) addRoot(id, "unknown"); break; }
                case 0x01: { long id = in.id(); in.id(); if (firstPass) addRoot(id, "JNI global"); break; }
                case 0x02: { long id = in.id(); in.u4(); in.u4(); if (firstPass) addRoot(id, "JNI local"); break; }
                case 0x03: { long id = in.id(); in.u4(); in.u4(); if (firstPass) addRoot(id, "Java frame"); break; }
                case 0x04: { long id = in.id(); in.u4(); if (firstPass) addRoot(id, "native stack"); break; }
                case 0x05: { long id = in.id(); if (firstPass) addRoot(id, "sticky class"); break; }
                case 0x06: { long id = in.id(); in.u4(); if (firstPass) addRoot(id, "thread block"); break; }
                case 0x07: { long id = in.id(); if (firstPass) addRoot(id, "monitor used"); break; }
                case 0x08: { long id = in.id(); in.u4(); in.u4(); if (firstPass) addRoot(id, "thread object"); break; }
                case 0x89: { long id = in.id(); if (firstPass) addRoot(id, "interned string"); break; }
                case 0x8a: { long id = in.id(); if (firstPass) addRoot(id, "finalizing"); break; }
                case 0x8b: { long id = in.id(); if (firstPass) addRoot(id, "debugger"); break; }
                case 0x8c: { long id = in.id(); if (firstPass) addRoot(id, "reference cleanup"); break; }
                case 0x8d: { long id = in.id(); if (firstPass) addRoot(id, "VM internal"); break; }
                case 0x8e: { long id = in.id(); in.u4(); in.u4(); if (firstPass) addRoot(id, "JNI monitor"); break; }
                case 0x90: { in.id(); break; }  // ROOT_UNREACHABLE marks objects that are NOT rooted: never a root
                case 0xfe: { in.u4(); in.id(); break; }
                case 0x20: {
                    ClassInfo c = new ClassInfo(); c.id = in.id(); in.u4(); c.superId = in.id(); c.loaderId = in.id();
                    c.signersId = in.id(); c.protectionDomainId = in.id(); in.id(); in.id(); in.u4();
                    int cp = in.u2(); List<Long> cpRefs = new ArrayList<>();
                    for (int i = 0; i < cp; i++) { in.u2(); int t = in.u1(); if (t == 2) { long val = in.id(); if (val != 0) cpRefs.add(val); } else in.skip(typeSize(t)); }
                    c.cpRefVals = cpRefs.stream().mapToLong(Long::longValue).toArray();
                    int ns = in.u2(); List<long[]> srefs = new ArrayList<>();
                    for (int i = 0; i < ns; i++) { long n = in.id(); int t = in.u1(); if (t == 2) { long val = in.id(); if (val != 0) srefs.add(new long[]{n, val}); } else in.skip(typeSize(t)); }
                    int nf = in.u2(); c.fieldNames = new long[nf]; c.fieldTypes = new byte[nf];
                    for (int i = 0; i < nf; i++) { c.fieldNames[i] = in.id(); c.fieldTypes[i] = (byte) in.u1(); }
                    c.staticRefNames = new long[srefs.size()]; c.staticRefVals = new long[srefs.size()];
                    for (int i = 0; i < srefs.size(); i++) { c.staticRefNames[i] = srefs.get(i)[0]; c.staticRefVals[i] = srefs.get(i)[1]; }
                    if (firstPass) classes.put(c.id, c);
                    v.classDump(firstPass ? c : classes.get(c.id));
                    break;
                }
                case 0x21: { long id = in.id(); in.u4(); long cid = in.id(); int len = in.u4(); v.instance(id, cid, in, len); break; }
                case 0x22: { long id = in.id(); in.u4(); int n = in.u4(); long acid = in.id(); v.objArray(id, acid, in, n); break; }
                case 0x23: { in.id(); in.u4(); int n = in.u4(); int t = in.u1(); in.skip((long) n * typeSize(t)); break; }
                default: throw new IllegalStateException("subtag " + Integer.toHexString(st) + " at " + in.pos());
            }
        }
    }

    /** How a class object references {@code id}: the same edges pass 2 builds for a CLASS_DUMP. */
    static String classEdgeLabel(ClassInfo c, long id) {
        for (int s = 0; s < c.staticRefVals.length; s++) if (c.staticRefVals[s] == id) return "static " + strings.get(c.staticRefNames[s]);
        for (long v : c.cpRefVals) if (v == id) return "<constant pool>";
        if (c.superId == id) return "<superclass>";
        if (c.loaderId == id) return "<classloader>";
        if (c.signersId == id) return "<signers>";
        if (c.protectionDomainId == id) return "<protection domain>";
        return "<class edge>";
    }

    static void addRoot(long id, String kind) { roots.add(id); rootKind.putIfAbsent(id, kind); }

    static String cname(long cid) {
        ClassInfo c = classes.get(cid); if (c != null && c.name != null) return c.name;
        Long sid = classNameId.get(cid); String n = sid == null ? "?" + Long.toHexString(cid) : strings.getOrDefault(sid, "?").replace('/', '.');
        if (c != null) c.name = n; return n;
    }

    static void layout(ClassInfo c) {
        if (c.dataSize >= 0) return;
        List<Integer> offs = new ArrayList<>(); List<String> labels = new ArrayList<>(); int off = 0;
        for (ClassInfo k = c; k != null; k = classes.get(k.superId)) {
            String kn = cname(k.id);
            for (int i = 0; i < k.fieldNames.length; i++) {
                String fn = strings.getOrDefault(k.fieldNames[i], "?");
                if (k.fieldTypes[i] == 2) {
                    String label = kn + "#" + fn;
                    boolean weak = kn.equals("java.lang.ref.Reference") && fn.equals("referent");
                    if (!weak && !excluded.contains(label)) { offs.add(off); labels.add(fn); }
                }
                off += typeSize(k.fieldTypes[i]);
            }
        }
        c.refOffsets = offs.stream().mapToInt(Integer::intValue).toArray(); c.refLabels = labels.toArray(new String[0]); c.dataSize = off;
    }

    static long[] ids; static int n;
    static int index(long id) { int i = Arrays.binarySearch(ids, 0, n, id); return i < 0 ? -1 : i; }

    public static void main(String[] a) throws Exception {
        Path p = Paths.get(a[0]); String target = a[1];
        for (int i = 2; i < a.length; i++) excluded.add(a[i]);
        long t0 = System.currentTimeMillis();
        ids = new long[Integer.getInteger("cap", 110_000_000)];
        System.err.println("pass 1: strings, classes, roots, object ids");
        walk(p, true, new Visitor() {
            public void instance(long id, long cid, In in, int len) throws IOException { add(id); in.skip(len); }
            public void objArray(long id, long acid, In in, int cnt) throws IOException { add(id); in.skip((long) cnt * idSize); }
            public void classDump(ClassInfo c) { add(c.id); }
            void add(long id) {
                if (n == ids.length) {
                    long grown = Math.min((long) ids.length + ids.length / 2 + 1, Integer.MAX_VALUE - 8);
                    if (grown <= ids.length) throw new IllegalStateException("too many objects for one id array: " + n);
                    System.err.printf("  growing id array to %d (set -Dcap above the object count to skip this)%n", grown);
                    ids = Arrays.copyOf(ids, (int) grown);
                }
                ids[n++] = id;
            }
        });
        Arrays.sort(ids, 0, n);
        for (ClassInfo c : classes.values()) layout(c);
        System.err.printf("objects (no prim arrays) %d, classes %d, roots %d, strings %d, %ds%n", n, classes.size(), roots.size(), strings.size(), (System.currentTimeMillis() - t0) / 1000);

        int[] off = new int[n + 1];
        final int[][] edges = new int[1][];
        final boolean[] fill = {false};
        final int[] cursor = new int[1];
        int[] objClass = null; // not stored; classes resolved in final pass
        Visitor ev = new Visitor() {
            void edge(int from, long to) { if (to == 0) return; int t = index(to); if (t < 0) return; if (fill[0]) edges[0][off[from] + cursor[0]++] = t; else off[from + 1]++; }
            public void instance(long id, long cid, In in, int len) throws IOException {
                int from = index(id); cursor[0] = 0; ClassInfo c = classes.get(cid);
                if (c == null || c.refOffsets.length == 0) { in.skip(len); return; }
                ByteBuffer b = in.payload(len);
                for (int o : c.refOffsets) edge(from, in.ref(b, o));
            }
            public void objArray(long id, long acid, In in, int cnt) throws IOException {
                int from = index(id); cursor[0] = 0; for (int i = 0; i < cnt; i++) edge(from, in.id());
            }
            public void classDump(ClassInfo c) {
                int from = index(c.id); cursor[0] = 0;
                for (long v : c.staticRefVals) edge(from, v);
                for (long v : c.cpRefVals) edge(from, v);
                edge(from, c.superId);
                edge(from, c.loaderId);
                edge(from, c.signersId);
                edge(from, c.protectionDomainId);
            }
        };
        System.err.println("pass 2: count edges");
        walk(p, false, ev);
        long totalEdges = 0;
        for (int i = 0; i < n; i++) totalEdges += off[i + 1];
        if (totalEdges > Integer.MAX_VALUE - 8) throw new IllegalStateException("too many edges for int offsets: " + totalEdges);
        for (int i = 0; i < n; i++) off[i + 1] += off[i];
        System.err.printf("edges %d, %ds%n", off[n], (System.currentTimeMillis() - t0) / 1000);
        edges[0] = new int[off[n]]; fill[0] = true;
        System.err.println("pass 3: fill edges");
        walk(p, false, ev);

        System.err.println("BFS from roots");
        int[] parent = new int[n]; Arrays.fill(parent, -2); int[] q = new int[n]; int qh = 0, qt = 0;
        // thread objects / frames first so paths prefer them, then classes
        for (long r : roots) { int i = index(r); if (i >= 0 && parent[i] == -2) { parent[i] = -1; q[qt++] = i; } }
        for (long cid : classes.keySet()) { int i = index(cid); if (i >= 0 && parent[i] == -2) { parent[i] = -1; q[qt++] = i; } }
        while (qh < qt) { int u = q[qh++]; for (int e = off[u]; e < off[u + 1]; e++) { int v = edges[0][e]; if (parent[v] == -2) { parent[v] = u; q[qt++] = v; } } }
        System.err.printf("reachable %d of %d, %ds%n", qt, n, (System.currentTimeMillis() - t0) / 1000);
        q = null;

        // find target instances + their paths; then one more pass to label nodes
        Map<Long, Integer> want = new HashMap<>(); List<Long> targets = new ArrayList<>();
        Set<Long> targetClassIds = new HashSet<>();
        for (ClassInfo c : classes.values()) if (cname(c.id).equals(target)) targetClassIds.add(c.id);
        final Map<Long, Long> nodeClass = new HashMap<>(); final Map<Long, long[]> nodeRefs = new HashMap<>();
        walk(p, false, new Visitor() {
            public void instance(long id, long cid, In in, int len) throws IOException { if (targetClassIds.contains(cid)) targets.add(id); in.skip(len); }
        });
        List<int[]> paths = new ArrayList<>(); Set<Long> pathIds = new HashSet<>();
        for (long t : targets) {
            int i = index(t); List<Integer> path = new ArrayList<>();
            if (parent[i] == -2) { paths.add(new int[]{i}); pathIds.add(t); continue; }
            for (int u = i; u >= 0; u = parent[u]) path.add(u);
            Collections.reverse(path); int[] pa = path.stream().mapToInt(Integer::intValue).toArray(); paths.add(pa);
            for (int u : pa) pathIds.add(ids[u]);
        }
        System.err.println("pass 5: label path nodes");
        final Map<Long, String> label = new HashMap<>();
        walk(p, false, new Visitor() {
            public void instance(long id, long cid, In in, int len) throws IOException {
                if (!pathIds.contains(id)) { in.skip(len); return; }
                ClassInfo c = classes.get(cid); nodeClass.put(id, cid); ByteBuffer b = in.payload(len);
                long[] r = new long[c.refOffsets.length];
                for (int k = 0; k < r.length; k++) r[k] = in.ref(b, c.refOffsets[k]);
                nodeRefs.put(id, r);
            }
            public void objArray(long id, long acid, In in, int cnt) throws IOException {
                if (!pathIds.contains(id)) { in.skip((long) cnt * idSize); return; }
                nodeClass.put(id, acid); long[] r = new long[cnt]; for (int k = 0; k < cnt; k++) r[k] = in.id(); nodeRefs.put(id, r);
            }
        });
        System.out.printf("target %s: %d instances%n", target, targets.size());
        // Group paths that take the same route (same root kind, fields and classes; array indices and
        // addresses ignored), so thousands of instances read as a handful of routes with counts.
        // -Dgroup=false prints every path individually instead.
        boolean group = !"false".equals(System.getProperty("group"));
        Map<String, Integer> routeCount = new HashMap<>(); Map<String, String> routeExample = new HashMap<>();
        Map<String, Set<Long>> routeHolders = new HashMap<>(); // distinct objects at path position 1..n-2, for the "via how many" column
        for (int[] pa : paths) {
            long tid = ids[pa[pa.length - 1]];
            StringBuilder full = new StringBuilder(); StringBuilder sig = new StringBuilder();
            full.append(String.format("%n=== %s @0x%x  (path length %d)%n", target, tid, pa.length));
            if (pa.length == 1 && parent[pa[0]] == -2) {
                full.append("  UNREACHABLE (garbage / only weakly reachable)\n"); sig.append("UNREACHABLE");
            } else for (int k = 0; k < pa.length; k++) {
                long id = ids[pa[k]]; String via = "";
                if (k > 0) {
                    long pid = ids[pa[k - 1]]; long[] r = nodeRefs.get(pid); Long pc = nodeClass.get(pid);
                    if (classes.containsKey(pid)) via = classEdgeLabel(classes.get(pid), id);
                    else if (r != null && pc != null) {
                        ClassInfo c = classes.get(pc);
                        if (cname(pc).startsWith("[")) { for (int s = 0; s < r.length; s++) if (r[s] == id) { via = "[" + s + "]"; break; } }
                        else for (int s = 0; s < r.length; s++) if (r[s] == id) { via = "." + c.refLabels[s]; break; }
                    }
                }
                String cls = classes.containsKey(id) ? "class " + cname(id) : (nodeClass.containsKey(id) ? cname(nodeClass.get(id)) : "?");
                String root = k == 0 ? "ROOT(" + rootKind.getOrDefault(id, "class") + ") " : "";
                full.append(String.format("  %s%-28s %s @0x%x%n", root, via, cls, id));
                sig.append(root).append(via.startsWith("[") ? "[]" : via).append(' ').append(cls).append('\n');
            }
            if (!group) { System.out.print(full); continue; }
            String s = sig.toString();
            routeCount.merge(s, 1, Integer::sum); routeExample.putIfAbsent(s, full.toString());
            if (pa.length > 2) routeHolders.computeIfAbsent(s, x -> new HashSet<>()).add(ids[pa[pa.length - 2]]);
        }
        if (group) {
            List<String> routes = new ArrayList<>(routeCount.keySet());
            routes.sort((x, y) -> routeCount.get(y) - routeCount.get(x));
            System.out.printf("%d distinct routes%n", routes.size());
            for (String r : routes) {
                Set<Long> h = routeHolders.get(r);
                System.out.printf("%n##### %d instances via this route (%d distinct direct holders); example:%s",
                    routeCount.get(r), h == null ? 0 : h.size(), routeExample.get(r));
            }
        }
        System.err.printf("done %ds%n", (System.currentTimeMillis() - t0) / 1000);
    }
}
