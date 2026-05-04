package com.yourname.androidbuildbooster.model

data class ModuleNode(
    val name: String,                                    // :app
    val dirPath: String,                                 // absolute path on disk
    val dependencies: List<String> = emptyList(),        // other module names this depends on
    val isDirectlyChanged: Boolean = false,              // has uncommitted changes
    val isTransitivelyAffected: Boolean = false,         // depends on a changed module
    val changedFiles: List<String> = emptyList()         // changed file paths within this module
) {
    val isAffected: Boolean get() = isDirectlyChanged || isTransitivelyAffected
}
