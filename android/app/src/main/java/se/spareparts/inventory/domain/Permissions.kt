package se.spareparts.inventory.domain

/**
 * What the signed-in account is allowed to do, as sent by the server in `perms`.
 *
 * The server is the one that enforces this; the app uses it only to keep controls the person may
 * not use off the screen. Pure Kotlin so the mapping is unit tested.
 */
@JvmInline
value class Permissions(val perms: Set<String>) {

    fun has(perm: String): Boolean = perm in perms

    /** May see the inventory at all. */
    val canView: Boolean get() = has(VIEW)

    /** May book stock: the −/+ stepper, Take, Return, Receive and Set count. */
    val canAdjust: Boolean get() = has(ADJUST)

    /** May edit location, notes and the minimum quantity. */
    val canEditLight: Boolean get() = has(EDIT_LIGHT) || has(EDIT)

    /** May edit any field and create parts. */
    val canEdit: Boolean get() = has(EDIT)

    val canDelete: Boolean get() = has(DELETE)

    /** May upload manuals and other files. */
    val canUploadFiles: Boolean get() = has(FILES)

    val canImport: Boolean get() = has(IMPORT)

    val canManageUsers: Boolean get() = has(USERS)

    /** Nothing can be changed from the app – the detail screen shows a "Read only" chip. */
    val readOnly: Boolean get() = !canAdjust && !canEditLight

    /** Which permission a PATCH on [field] needs, mirroring the server's LIGHT_FIELDS rule. */
    fun canEditField(field: String): Boolean =
        if (field in LIGHT_FIELDS) canEditLight else canEdit

    override fun toString(): String = perms.sorted().joinToString(", ")

    companion object {
        const val VIEW = "view"
        const val ADJUST = "adjust"
        const val EDIT_LIGHT = "edit_light"
        const val EDIT = "edit"
        const val DELETE = "delete"
        const val FILES = "files"
        const val IMPORT = "import"
        const val USERS = "users"

        /** The fields `edit_light` covers on the server. */
        val LIGHT_FIELDS = setOf("location", "notes", "min_qty")

        val NONE = Permissions(emptySet())

        fun of(list: List<String>?): Permissions =
            Permissions(list.orEmpty().mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toSet())
    }
}
