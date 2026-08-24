package com.creategravitybatteries;

import java.util.ArrayList;
import java.util.List;

import net.createmod.catnip.lang.Lang;
import net.createmod.catnip.lang.LangBuilder;
import net.minecraft.network.chat.Component;

/**
 * This mod's own namespace for Create's tooltip builder, so goggle overlays line up with Create's
 * without borrowing its lang keys. {@code CreateLang.translate} would resolve {@code create.*}.
 */
public class GBLang extends Lang {

	public static LangBuilder builder() {
		return Lang.builder(CreateGravityBatteries.ID);
	}

	public static LangBuilder translate(String key, Object... args) {
		return builder().translate(key, args);
	}

	/**
	 * The option labels for a scroll input, as {@code <prefix>.<option>}. Create has this on
	 * {@code CreateLang}, but that one resolves in Create's namespace.
	 */
	public static List<Component> translatedOptions(String prefix, String... options) {
		List<Component> result = new ArrayList<>(options.length);
		for (String option : options)
			result.add(translate(prefix + "." + option)
				.component());
		return result;
	}
}
