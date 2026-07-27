package dev.qelg.harnessandroid

internal fun treeParentAfterSelection(
    currentTreeParentId: String?,
    selectedFromTree: Boolean,
): String? = if (selectedFromTree) currentTreeParentId else null
