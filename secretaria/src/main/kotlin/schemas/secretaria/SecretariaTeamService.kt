package schemas.secretaria

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

private const val MIN_PASSWORD = 10

/** Equipe da clínica, perfil e senha ("Meu perfil" e Configurações > Equipe). */
class SecretariaTeamService(
    private val database: Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun list(clinicId: Long): List<TeamMemberDto> = database.dbQuery {
        (ClinicUsersTable innerJoin UsersTable).selectAll()
            .where { ClinicUsersTable.clinicId eq clinicId }
            .orderBy(UsersTable.name)
            .map { it.toMember() }
    }

    suspend fun add(clinicId: Long, request: AddTeamMemberRequest): ServiceResult<TeamMemberDto> {
        val role = parseRole(request.role) ?: return invalid("Papel inválido (admin, secretaria ou profissional).")
        val email = request.email.trim().lowercase()
        if (!email.contains('@') || email.length > 160) return invalid("E-mail inválido.")

        return database.dbQuery {
            val existing = UsersTable.selectAll().where { UsersTable.email eq email }.singleOrNull()
            val userId = if (existing != null) {
                if (!existing[UsersTable.active]) return@dbQuery invalid("Esse usuário está desativado.")
                val already = !ClinicUsersTable.selectAll()
                    .where { (ClinicUsersTable.clinicId eq clinicId) and (ClinicUsersTable.userId eq existing[UsersTable.id]) }.empty()
                if (already) return@dbQuery conflict("Esse usuário já faz parte da clínica.")
                existing[UsersTable.id]
            } else {
                val name = request.name?.trim()
                if (name.isNullOrEmpty() || name.length > 120) return@dbQuery invalid("Informe o nome do usuário.")
                val password = request.password.orEmpty()
                if (password.length < MIN_PASSWORD) return@dbQuery invalid("A senha deve ter ao menos $MIN_PASSWORD caracteres.")
                UsersTable.insert {
                    it[UsersTable.name] = name
                    it[UsersTable.email] = email
                    it[passwordHash] = SecretariaPasswords.hash(password)
                    it[createdAt] = clock()
                }[UsersTable.id]
            }
            ClinicUsersTable.insert {
                it[ClinicUsersTable.clinicId] = clinicId
                it[ClinicUsersTable.userId] = userId
                it[ClinicUsersTable.role] = role
            }
            ServiceResult.Ok(memberOf(clinicId, userId))
        }
    }

    suspend fun changeRole(clinicId: Long, userId: Long, roleName: String): ServiceResult<TeamMemberDto> {
        val role = parseRole(roleName) ?: return invalid("Papel inválido (admin, secretaria ou profissional).")
        return database.dbQuery {
            val current = membership(clinicId, userId) ?: return@dbQuery notFound("Usuário não encontrado nesta clínica.")
            if (current == ClinicRole.ADMIN && role != ClinicRole.ADMIN && adminCount(clinicId) <= 1) {
                return@dbQuery conflict("A clínica precisa de pelo menos um administrador.")
            }
            ClinicUsersTable.update({ (ClinicUsersTable.clinicId eq clinicId) and (ClinicUsersTable.userId eq userId) }) { it[ClinicUsersTable.role] = role }
            ServiceResult.Ok(memberOf(clinicId, userId))
        }
    }

    /** Desvincula o usuário da clínica (a conta continua existindo, inclusive em outras clínicas). */
    suspend fun remove(clinicId: Long, userId: Long): ServiceResult<Unit> = database.dbQuery {
        val current = membership(clinicId, userId) ?: return@dbQuery notFound("Usuário não encontrado nesta clínica.")
        if (current == ClinicRole.ADMIN && adminCount(clinicId) <= 1) return@dbQuery conflict("A clínica precisa de pelo menos um administrador.")
        NotificationsTable.update({ (NotificationsTable.clinicId eq clinicId) and (NotificationsTable.userId eq userId) }) { it[NotificationsTable.userId] = null }
        ClinicUsersTable.deleteWhere { (ClinicUsersTable.clinicId eq clinicId) and (ClinicUsersTable.userId eq userId) }
        ServiceResult.Ok(Unit)
    }

    // ── perfil e senha ───────────────────────────────────────────────────────

    suspend fun updateProfile(userId: Long, name: String): ServiceResult<UserDto> {
        val trimmed = name.trim()
        if (trimmed.length < 2 || trimmed.length > 120) return invalid("Nome inválido (2 a 120 caracteres).")
        return database.dbQuery {
            UsersTable.update({ UsersTable.id eq userId }) { it[UsersTable.name] = trimmed }
            val row = UsersTable.selectAll().where { UsersTable.id eq userId }.single()
            ServiceResult.Ok(UserDto(row[UsersTable.id], row[UsersTable.name], row[UsersTable.email]))
        }
    }

    suspend fun changePassword(userId: Long, current: String, new: String): ServiceResult<Unit> {
        if (new.length < MIN_PASSWORD) return invalid("A nova senha deve ter ao menos $MIN_PASSWORD caracteres.")
        if (new == current) return invalid("A nova senha deve ser diferente da atual.")
        return database.dbQuery {
            val hash = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()?.get(UsersTable.passwordHash)
                ?: return@dbQuery notFound("Usuário não encontrado.")
            if (!SecretariaPasswords.verify(current, hash)) return@dbQuery ServiceResult.Err(ErrorKind.FORBIDDEN, "Senha atual incorreta.")
            UsersTable.update({ UsersTable.id eq userId }) { it[passwordHash] = SecretariaPasswords.hash(new) }
            ServiceResult.Ok(Unit)
        }
    }

    // ── internos ─────────────────────────────────────────────────────────────

    private fun parseRole(name: String): ClinicRole? = ClinicRole.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

    private fun Transaction.membership(clinicId: Long, userId: Long): ClinicRole? =
        ClinicUsersTable.selectAll().where { (ClinicUsersTable.clinicId eq clinicId) and (ClinicUsersTable.userId eq userId) }
            .singleOrNull()?.get(ClinicUsersTable.role)

    private fun Transaction.adminCount(clinicId: Long): Long =
        ClinicUsersTable.selectAll().where { (ClinicUsersTable.clinicId eq clinicId) and (ClinicUsersTable.role eq ClinicRole.ADMIN) }.count()

    private fun Transaction.memberOf(clinicId: Long, userId: Long): TeamMemberDto =
        (ClinicUsersTable innerJoin UsersTable).selectAll()
            .where { (ClinicUsersTable.clinicId eq clinicId) and (ClinicUsersTable.userId eq userId) }.single().toMember()

    private fun ResultRow.toMember() = TeamMemberDto(
        userId = this[UsersTable.id],
        name = this[UsersTable.name],
        email = this[UsersTable.email],
        role = this[ClinicUsersTable.role].name.lowercase(),
        lastLoginAt = this[UsersTable.lastLoginAt],
    )
}
