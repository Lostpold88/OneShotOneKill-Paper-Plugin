package de.oneshotonekill.manager

import net.kyori.adventure.sound.Sound
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound as BukkitSound

class KillEffectManager {

    /**
     * Spielt nativ ueber die Paper Particle & Sound Engine den Blut-Splash-Killeffekt an der
     * Position ab.
     */
    fun playKillEffect(deathLoc: Location?) {
        val world = deathLoc?.world ?: return
        val particleLoc = deathLoc.clone().add(0.0, 1.0, 0.0)

        // 1. Rote Staub-Partikel fuer dichten Blutspritzer
        val redDust = Particle.DustOptions(Color.fromRGB(180, 0, 0), 2.2f)
        world.spawnParticle(Particle.DUST, particleLoc, 75, 0.4, 0.6, 0.4, 0.1, redDust)

        // 2. Redstone-Block Partikel fuer spritzende Blutpartikel
        world.spawnParticle(
            Particle.BLOCK, particleLoc, 50, 0.3, 0.5, 0.3, 0.1,
            Material.REDSTONE_BLOCK.createBlockData(),
        )

        // 3. Native Audio-Feedback
        world.playSound(
            Sound.sound(BukkitSound.ENTITY_SQUID_SQUIRT, Sound.Source.MASTER, 1.0f, 0.7f),
            deathLoc.x(), deathLoc.y(), deathLoc.z(),
        )
        world.playSound(
            Sound.sound(BukkitSound.ENTITY_PLAYER_HURT, Sound.Source.MASTER, 0.8f, 0.6f),
            deathLoc.x(), deathLoc.y(), deathLoc.z(),
        )
    }
}
