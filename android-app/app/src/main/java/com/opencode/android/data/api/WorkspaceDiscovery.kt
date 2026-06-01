package com.opencode.android.data.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class WorkspaceOption(
    val label: String,
    val path: String,
    val sessionCount: Int = 0,
)

suspend fun OpenCodeApi.fetchWorkspaceOptions(): Result<List<WorkspaceOption>> = runCatching {
    coroutineScope {
        val projects = fetchProjects().getOrThrow()
        val baseOptions = projects.flatMap { project ->
            val root = WorkspaceOption(
                label = project.worktree.pathLabel(),
                path = project.worktree,
            )
            val sandboxes = project.sandboxes.map { sandbox ->
                WorkspaceOption(
                    label = "${project.worktree.pathLabel()} / ${sandbox.pathLabel()}",
                    path = sandbox,
                )
            }
            val children = fetchProjectRootFiles(project.worktree)
                .getOrNull()
                .orEmpty()
                .filter { it.type == "directory" }
                .map {
                    WorkspaceOption(
                        label = "${project.worktree.pathLabel()} / ${it.name}",
                        path = it.absolute,
                    )
                }
            listOf(root) + sandboxes + children
        }
            .distinctBy { it.path }

        baseOptions
            .map { option ->
                async {
                    option.copy(
                        sessionCount = listSessions(directory = option.path)
                            .getOrNull()
                            .orEmpty()
                            .size
                    )
                }
            }
            .awaitAll()
            .hideEmptyParentsWithSessionChildren()
            .sortedWith(
                compareByDescending<WorkspaceOption> { it.sessionCount }
                    .thenBy { it.label.lowercase() }
            )
    }
}

private fun List<WorkspaceOption>.hideEmptyParentsWithSessionChildren(): List<WorkspaceOption> {
    val hiddenParents = filter { it.sessionCount > 0 }
        .flatMap { child ->
            filter { parent ->
                parent.sessionCount == 0 &&
                    child.path != parent.path &&
                    child.path.startsWith(parent.path.trimEnd('/') + "/")
            }
        }
        .map { it.path }
        .toSet()
    return filterNot { it.path in hiddenParents }
}

private fun String.pathLabel(): String =
    trimEnd('/').substringAfterLast('/').ifBlank { this }
