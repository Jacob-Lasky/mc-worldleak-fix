package io.github.jacoblasky.worldleakfix;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Registers both mixin configs with MixinBooter as EARLY mixins, so they are queued before
 * {@code net.minecraft.client.Minecraft} or Modular Machinery's {@code TaskExecutor} is loaded; a
 * mixin that arrives after its target class has loaded silently does not apply.
 *
 * <p>Both configs are {@code "required": false} on purpose. This runs
 * in a player's client, where a required-mixin failure is a crash at launch with no fix to hand, while
 * a no-op only leaves the old leak in place. A no-op is still visible: every hollowed world logs a
 * {@code [WorldLeakFix]} line in latest.log, so its absence after a dimension change is the signal.
 *
 * <p>No {@code @Mod} class, so the jar stays out of the handshake; the server needs nothing.
 */
@IFMLLoadingPlugin.Name("World Leak Fix")
@IFMLLoadingPlugin.MCVersion("1.12.2")
// >1000 so this sorts after MixinBooter's own plugin, which must be up before its
// IEarlyMixinLoader scan can see this class.
@IFMLLoadingPlugin.SortingIndex(1001)
public class CorePlugin implements IFMLLoadingPlugin, IEarlyMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Arrays.asList("mixins.worldleakfix.json", "mixins.worldleakfix.mmce.json");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        // nothing to inject; all behaviour lives in the mixins
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
