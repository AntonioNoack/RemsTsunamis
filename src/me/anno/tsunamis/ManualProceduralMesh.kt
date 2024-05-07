package me.anno.tsunamis

import me.anno.ecs.components.mesh.Mesh
import me.anno.ecs.components.mesh.ProceduralMesh

class ManualProceduralMesh: ProceduralMesh() {
    override fun generateMesh(mesh: Mesh) {}
}