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
        val rootSessions = listSessions(roots = true).getOrNull().orEmpty()
        val sessionOptions = rootSessions
            .mapNotNull { session ->
                session.directory?.takeIf { it.isNotBlank() }?.let { directory ->
                    WorkspaceOption(
                        label = directory.pathLabel(),
                        path = directory,
                        sessionCount = session.messageCount ?: 0,
                    )
                }
            }
            .mergeSessionCounts()

        val projects = fetchProjects().getOrNull().orEmpty()
        val projectOptions = projects.flatMap { project ->
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

        val baseOptions = (sessionOptions + projectOptions)
            .distinctBy { it.path }

        baseOptions
            .map { option ->
                async {
                    val sessionCount = listSessions(directory = option.path)
                        .getOrNull()
                        .orEmpty()
                        .size
                    option.copy(sessionCount = maxOf(option.sessionCount, sessionCount))
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

private fun List<WorkspaceOption>.mergeSessionCounts(): List<WorkspaceOption> =
    groupBy { it.path }
        .map { (path, options) ->
            val first = options.first()
            first.copy(
                label = first.label.ifBlank { path.pathLabel() },
                sessionCount = options.sumOf { it.sessionCount },
            )
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
