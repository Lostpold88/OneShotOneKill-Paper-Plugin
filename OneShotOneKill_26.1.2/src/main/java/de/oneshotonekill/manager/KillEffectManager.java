package de.oneshotonekill.manager;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class KillEffectManager {

    public enum KillEffect {
        LIGHTNING("<yellow>[⚡] Blitzschlag</yellow>"),
        FIREWORK("<red>[🎆] Feuerwerks-Explosion</red>"),
        BLOOD("<dark_red>[🩸] Blut-Splash</dark_red>"),
        ENDER("<light_purple>[🔮] Ender-Portal</light_purple>"),
        TOTEM("<green>[✨] Totem-Aura</green>"),
        NONE("<gray>[❌] Keine Animation</gray>");

        private final Component displayName;

        KillEffect(String miniMessageFormat) {
            this.displayName = MiniMessage.miniMessage().deserialize(miniMessageFormat);
        }

        public Component getDisplayName() {
            return displayName;
        }
    }

    private final Map<UUID, KillEffect> selectedEffects = new HashMap<>();

    public KillEffect getSelectedEffect(UUID uuid) {
        return selectedEffects.getOrDefault(uuid, KillEffect.LIGHTNING);
    }

    public void setSelectedEffect(UUID uuid, KillEffect effect) {
        selectedEffects.put(uuid, effect);
    }

    public void playKillEffect(Player killer, Location deathLoc) {
        KillEffect effect = getSelectedEffect(killer.getUniqueId());
        if (deathLoc == null || deathLoc.getWorld() == null) return;

        switch (effect) {
            case LIGHTNING -> {
                deathLoc.getWorld().strikeLightningEffect(deathLoc);
            }
            case FIREWORK -> {
                deathLoc.getWorld().spawnParticle(Particle.FIREWORK, deathLoc.clone().add(0, 1, 0), 40, 0.4, 0.4, 0.4, 0.1);
                deathLoc.getWorld().playSound(deathLoc, Sound.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.MASTER, 1.0f, 1.0f);
            }
            case BLOOD -> {
                Particle.DustOptions redDust = new Particle.DustOptions(Color.RED, 1.8f);
                deathLoc.getWorld().spawnParticle(Particle.DUST, deathLoc.clone().add(0, 1, 0), 60, 0.4, 0.5, 0.4, 0.1, redDust);
                deathLoc.getWorld().playSound(deathLoc, Sound.ENTITY_SQUID_SQUIRT, SoundCategory.MASTER, 1.0f, 0.8f);
            }
            case ENDER -> {
                deathLoc.getWorld().spawnParticle(Particle.DRAGON_BREATH, deathLoc.clone().add(0, 1, 0), 40, 0.4, 0.4, 0.4, 0.05);
                deathLoc.getWorld().spawnParticle(Particle.PORTAL, deathLoc.clone().add(0, 1, 0), 50, 0.5, 0.5, 0.5, 0.2);
                deathLoc.getWorld().playSound(deathLoc, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.MASTER, 1.0f, 0.6f);
            }
            case TOTEM -> {
                deathLoc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, deathLoc.clone().add(0, 1, 0), 60, 0.4, 0.5, 0.4, 0.2);
                deathLoc.getWorld().playSound(deathLoc, Sound.ITEM_TOTEM_USE, SoundCategory.MASTER, 0.8f, 1.2f);
            }
            case NONE -> {
                // Keine Spezial-Animation
            }
        }
    }
}
