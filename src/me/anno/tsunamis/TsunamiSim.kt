package me.anno.tsunamis

import me.anno.engine.RemsEngine
import me.anno.extensions.ExtensionLoader
import me.anno.extensions.mods.Mod
import me.anno.io.ISaveable.Companion.registerCustomClass
import me.anno.tsunamis.setups.CircularDamBreak
import me.anno.tsunamis.setups.LinearDamBreak

class TsunamiSim : Mod() {

    override fun onPreInit() {
        super.onPreInit()

        // register components
        registerCustomClass(FluidSim())
        registerCustomClass(LinearDamBreak())
        registerCustomClass(CircularDamBreak())

    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            ExtensionLoader.loadMainInfo()
            RemsEngine().run()
        }
    }

}