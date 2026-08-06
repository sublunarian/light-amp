package com.thelightphone.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UField
import org.jetbrains.uast.getContainingUClass

class LightJobDetector : Detector(), SourceCodeScanner {

    companion object {
        private const val ANNOTATION_FQN = "com.thelightphone.sdk.LightJob"
        private const val HANDLER_SIMPLE_NAME = "LightJobHandler"

        val ISSUE_LIGHT_JOB_INVALID = Issue.create(
            id = "LightSdkInvalidLightJob",
            briefDescription = "@LightJob must annotate a top-level val of type LightJobHandler",
            explanation = "Properties annotated with @LightJob are discovered by KSP and " +
                "registered for WorkManager dispatch. They must be top-level (not inside " +
                "a class or object), declared as `val`, and explicitly typed as LightJobHandler.",
            category = Category.CORRECTNESS,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                LightJobDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        val ISSUE_LIGHT_JOB_EMPTY_KEY = Issue.create(
            id = "LightSdkLightJobEmptyKey",
            briefDescription = "@LightJob key must be a non-empty string literal",
            explanation = "The key passed to @LightJob identifies the job at enqueue time " +
                "and must be a non-empty string.",
            category = Category.CORRECTNESS,
            priority = 7,
            severity = Severity.ERROR,
            implementation = Implementation(
                LightJobDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UField::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitField(node: UField) {
                val annotation = node.findAnnotation(ANNOTATION_FQN) ?: return
                val ktProperty = node.sourcePsi as? KtProperty

                val containingClass = node.getContainingUClass()?.javaPsi
                val isTopLevel = containingClass?.let {
                    it.containingClass == null && it.name?.endsWith("Kt") == true
                } ?: false

                val isVal = ktProperty?.isVar == false
                val typeText = ktProperty?.typeReference?.text?.substringAfterLast('.')?.trim()
                val typeMatches = typeText == HANDLER_SIMPLE_NAME

                if (!isTopLevel || !isVal || !typeMatches) {
                    val reasons = buildList {
                        if (!isTopLevel) add("not top-level")
                        if (!isVal) add("not a val")
                        if (!typeMatches) add("type is not $HANDLER_SIMPLE_NAME")
                    }
                    context.report(
                        ISSUE_LIGHT_JOB_INVALID,
                        node,
                        context.getLocation(node as UElement),
                        "@LightJob must annotate a top-level val of type $HANDLER_SIMPLE_NAME " +
                            "(${reasons.joinToString()})",
                    )
                }

                val keyValue = annotation.findAttributeValue("key")?.evaluate() as? String
                if (keyValue.isNullOrEmpty()) {
                    context.report(
                        ISSUE_LIGHT_JOB_EMPTY_KEY,
                        node,
                        context.getLocation(annotation),
                        "@LightJob key must be a non-empty string literal",
                    )
                }
            }
        }
    }
}
