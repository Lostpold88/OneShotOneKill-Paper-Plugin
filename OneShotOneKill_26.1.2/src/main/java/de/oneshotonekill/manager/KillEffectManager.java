package de.oneshotonekill.manager;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;

public class KillEffectManager {

    /**
     * Spielt nativ über die Paper Particle & Sound Engine den Blut-Splash-Killeffekt an der Position ab.
     */
    public void playKillEffect(Location deathLoc) {
        if (deathLoc == null || deathLoc.getWorld() == null) return;

        Location particleLoc = deathLoc.clone().add(0, 1, 0);

        // 1. Rote Staub-Partikel für dichten Blutspritzer
        Particle.DustOptions redDust = new Particle.DustOptions(Color.fromRGB(180, 0, 0), 2.2f);
        deathLoc.getWorld().spawnParticle(Particle.DUST, particleLoc, 75, 0.4, 0.6, 0.4, 0.1, redDust);

        // 2. Redstone-Block Partikel für spritzende Blutpartikel
        deathLoc.getWorld().spawnParticle(Particle.BLOCK, particleLoc, 50, 0.3, 0.5, 0.3, 0.1, Material.REDSTONE_BLOCK.createBlockData());

        // 3. Native Audio-Feedback
        deathLoc.getWorld().playSound(deathLoc, Sound.ENTITY_SQUID_SQUIRT, SoundCategory.MASTER, 1.0f, 0.7f);
        deathLoc.getWorld().playSound(deathLoc, Sound.ENTITY_PLAYER_HURT, SoundCategory.MASTER, 0.8f, 0.6f);
    }
}
