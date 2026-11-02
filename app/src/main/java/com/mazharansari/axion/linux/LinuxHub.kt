package com.mazharansari.axion.linux

/** Lets agent tools reach the Linux runtime without threading it through every constructor. */
object LinuxHub {
    @Volatile var graph: com.mazharansari.axion.Graph? = null
    val linux: LinuxRuntime? get() = graph?.linux
    val devtools: DevTools? get() = graph?.devtools
}
