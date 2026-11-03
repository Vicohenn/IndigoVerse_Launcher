package legend.limit;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LegendLimitMod implements ModInitializer {
	public static final String MOD_ID = "legendlimit";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		BarrelConverter.register();
		LimitConfig.load();
		UsageGuard.register();
		StarterLock.register();
		CommandRegistrationCallback.EVENT.register(LimitCommands::register);
	}
}