package de.oneshotonekill.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage

/**
 * Eine MiniMessage-Instanz fuer das ganze Plugin. `MiniMessage.miniMessage()` liefert ohnehin
 * einen Singleton; die Konstante spart nur den Aufruf im heissen Pfad.
 */
private val MINI_MESSAGE: MiniMessage = MiniMessage.miniMessage()

/**
 * Uebersetzt MiniMessage-Markup in eine [Component].
 *
 * Ersetzt das im ganzen Projekt wiederkehrende
 * `MiniMessage.miniMessage().deserialize("<red>…</red>")`. Vorgabe 9 aus `.agents/AGENTS.md`
 * bleibt unberuehrt - es bleibt derselbe MiniMessage-Parser, nur ohne die Wiederholung.
 */
fun String.mini(): Component = MINI_MESSAGE.deserialize(this)
