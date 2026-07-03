package io.orangebuffalo.renalo.tracking

import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.Patch
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.QueryValue
import io.micronaut.security.annotation.Secured
import io.micronaut.security.authentication.Authentication
import io.orangebuffalo.renalo.auth.UserRoles
import io.orangebuffalo.renalo.user.UserRepository

@Controller("/api/tracking/income-categories")
@Secured(UserRoles.USER)
class IncomeCategoryController(
    private val userRepository: UserRepository,
    private val incomeCategoryService: IncomeCategoryService,
) {
    @Get
    fun listCategories(
        authentication: Authentication,
        @QueryValue(defaultValue = "false") includeArchived: Boolean,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return HttpResponse.ok(
            incomeCategoryService.listCategories(user.id!!, includeArchived).map { it.toResponse() },
        )
    }

    @Get("/{categoryId}")
    fun getCategory(categoryId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val category = incomeCategoryService.findCategory(user.id!!, categoryId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(category.toResponse())
    }

    @Post
    fun createCategory(
        authentication: Authentication,
        @Body request: SaveIncomeCategoryRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val category = incomeCategoryService.createCategory(user.id!!, request)
            ?: return HttpResponse.badRequest<Any>()

        return HttpResponse.created(category.toResponse())
    }

    @Patch("/{categoryId}")
    fun updateCategory(
        categoryId: Long,
        authentication: Authentication,
        @Body request: SaveIncomeCategoryRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val existingCategory = incomeCategoryService.findCategory(user.id!!, categoryId)
            ?: return HttpResponse.notFound<Any>()
        val category = incomeCategoryService.updateCategory(user.id!!, existingCategory.id!!, request)
            ?: return HttpResponse.badRequest<Any>()

        return HttpResponse.ok(category.toResponse())
    }

    @Get("/{categoryId}/merge-summary")
    fun getMergeSummary(categoryId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val summary = incomeCategoryService.getMergeSummary(user.id!!, categoryId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(summary.toResponse())
    }

    @Post("/{categoryId}/merge")
    fun mergeCategory(
        categoryId: Long,
        authentication: Authentication,
        @Body request: MergeIncomeCategoryRequest,
    ): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()

        return when (incomeCategoryService.mergeCategory(user.id!!, categoryId, request)) {
            CategoryMergeResult.MERGED -> HttpResponse.noContent<Any>()
            CategoryMergeResult.NOT_FOUND -> HttpResponse.notFound<Any>()
            CategoryMergeResult.INVALID_TARGET -> HttpResponse.badRequest<Any>()
        }
    }

    @Post("/{categoryId}/archive")
    fun archiveCategory(categoryId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val category = incomeCategoryService.archiveCategory(user.id!!, categoryId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(category.toResponse())
    }

    @Post("/{categoryId}/unarchive")
    fun unarchiveCategory(categoryId: Long, authentication: Authentication): HttpResponse<*> {
        val user = userRepository.findByUsername(authentication.name)
            ?: return HttpResponse.unauthorized<Any>()
        val category = incomeCategoryService.unarchiveCategory(user.id!!, categoryId)
            ?: return HttpResponse.notFound<Any>()

        return HttpResponse.ok(category.toResponse())
    }
}

private fun IncomeCategory.toResponse() = IncomeCategoryResponse(
    id = id ?: error("Income category must be persisted before it can be returned"),
    name = name,
    archived = archived,
)

data class IncomeCategoryResponse(
    val id: Long,
    val name: String,
    val archived: Boolean,
)

private fun IncomeCategoryMergeSummary.toResponse() = IncomeCategoryMergeSummaryResponse(
    sourceCategory = sourceCategory.toResponse(),
    incomesCount = incomesCount,
    targetCategories = targetCategories.map { it.toResponse() },
)

data class IncomeCategoryMergeSummaryResponse(
    val sourceCategory: IncomeCategoryResponse,
    val incomesCount: Long,
    val targetCategories: List<IncomeCategoryResponse>,
)
