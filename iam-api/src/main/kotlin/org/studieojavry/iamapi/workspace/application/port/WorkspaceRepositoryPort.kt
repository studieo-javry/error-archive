package org.studieojavry.iamapi.workspace.application.port

import org.studieojavry.iamapi.workspace.domain.model.Workspace

interface WorkspaceRepositoryPort {
    fun save(workspace: Workspace): Workspace
    fun findById(id: Long): Workspace?
    fun delete(id: Long)
}