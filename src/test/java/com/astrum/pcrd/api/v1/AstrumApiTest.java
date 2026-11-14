package com.astrum.pcrd.api.v1;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import com.mojang.serialization.Lifecycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The facade is a public contract, so what it says must match what it does at every point of the
 * world lifecycle: empty before the first world, populated while one is loaded, empty again after.
 */
class AstrumApiTest {

    @AfterEach
    void clearBinding() {
        AstrumApi.unbind();
    }

    @Test
    void charactersIsEmptyBeforeAnyWorldLoads() {
        AstrumApi.unbind();
        assertTrue(AstrumApi.characters().isEmpty(), "首个世界加载前必须是空，不能是 null 或半成品");
    }

    @Test
    void bindThenUnbindMatchesTheWorldLifetime() {
        assertTrue(AstrumApi.characters().isEmpty());

        Registry<CharacterDef> registry = emptyCharacterRegistry();
        AstrumApi.bind(registry);
        assertSame(registry, AstrumApi.characters().orElseThrow(), "世界加载期间必须给出刚绑定的注册表");

        AstrumApi.unbind();
        assertTrue(AstrumApi.characters().isEmpty(), "离开世界后必须回到空，不能继续提供已关闭世界的注册表");
    }

    @Test
    void unbindIsIdempotent() {
        AstrumApi.unbind();
        assertDoesNotThrow(AstrumApi::unbind, "重复解绑不应抛异常，世界退出路径可能重复触发");
        assertTrue(AstrumApi.characters().isEmpty());
    }

    @Test
    void bindRejectsNull() {
        assertThrows(NullPointerException.class, () -> AstrumApi.bind(null));
    }

    private static Registry<CharacterDef> emptyCharacterRegistry() {
        MappedRegistry<CharacterDef> registry = new MappedRegistry<>(RegistryKeys.CHARACTERS, Lifecycle.stable());
        registry.freeze();
        return registry;
    }

    private static CharacterDef sampleDef() {
        return new CharacterDef(1, "姬塔", Dimension.CHARGE, Optional.empty(), Optional.empty(), List.of(),
                new UbDef(
                        new UbTier("tempest_blade", 1, 20.0, OptionalDouble.empty(), Optional.empty(),
                                OptionalDouble.empty(), Optional.empty()),
                        new UbTier("seiga_itten", 4, 20.0, OptionalDouble.empty(), Optional.empty(),
                                OptionalDouble.empty(), Optional.empty())),
                new GrowthDef(100, 1.15, 30),
                new AffectionDef(3, 8, List.of()),
                Optional.empty(), Optional.empty());
    }
}
