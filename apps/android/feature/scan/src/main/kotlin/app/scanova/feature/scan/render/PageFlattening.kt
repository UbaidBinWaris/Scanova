package app.scanova.feature.scan.render

import app.scanova.core.data.model.PageMesh

/** What the user chose for flattening a curled page. */
enum class FlattenChoice {
    /** Flattened when the model finds the page bent ([PageFlattening.BENT]): the default. */
    AUTO,

    /** Always flattened along the model's mesh. */
    ON,

    /** Never: flattened by the crop outline alone. */
    OFF,
}

/** The rule that decides whether a page is flattened along a mesh. Pure Kotlin, unit-tested. */
internal object PageFlattening {

    /**
     * A page whose mesh scores at least this ([MeshGeometry.bend]: distance
     * from a flat page in perspective, as a share of its diagonal) is curled.
     * Chosen on real photos (docs/ai-image-research.md): 91 % of Book100's
     * curved book pages score above it, 94 % of SmartDoc's flat pages below.
     * A flat page just above it is flattened along a mesh that is nearly a
     * perspective view anyway, so a wrong "bent" costs little; a missed one is
     * a tap on "Flatten".
     */
    const val BENT = 0.005

    /** The mesh a page keeps: none when off, the model's when on, and when automatic only if the page is bent. */
    fun keep(choice: FlattenChoice, mesh: PageMesh?, bend: Double): PageMesh? = when (choice) {
        FlattenChoice.OFF -> null
        FlattenChoice.ON -> mesh
        FlattenChoice.AUTO -> mesh?.takeIf { bend >= BENT }
    }
}
