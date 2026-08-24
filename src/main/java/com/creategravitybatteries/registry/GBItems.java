package com.creategravitybatteries.registry;

import com.creategravitybatteries.CreateGravityBatteries;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class GBItems {

	public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CreateGravityBatteries.ID);

	public static final DeferredRegister<CreativeModeTab> TABS =
		DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CreateGravityBatteries.ID);

	public static final DeferredItem<BlockItem> GRAVITY_BATTERY =
		ITEMS.registerSimpleBlockItem(GBBlocks.GRAVITY_BATTERY);

	public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main",
		() -> CreativeModeTab.builder()
			.title(Component.translatable("itemGroup.creategravitybatteries"))
			.icon(() -> GRAVITY_BATTERY.get()
				.getDefaultInstance())
			.displayItems((params, output) -> output.accept(GRAVITY_BATTERY.get()))
			.build());
}
