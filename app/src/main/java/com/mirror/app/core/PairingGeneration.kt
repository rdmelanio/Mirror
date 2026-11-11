package com.mirror.app.core

/** Invalidation and the persistent write share one monitor; old network results have no authority. */
internal class PairingGeneration {
    private var value=0
    @Synchronized fun begin():Int = ++value
    @Synchronized fun current():Int = value
    @Synchronized fun commit(attempt:Int, save:()->Boolean):Boolean = attempt==value && save()
}
