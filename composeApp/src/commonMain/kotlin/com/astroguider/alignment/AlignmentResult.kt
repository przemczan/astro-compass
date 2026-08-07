package com.astroguider.alignment

sealed interface AlignmentResult {
    data class Success(val model: AlignmentModel) : AlignmentResult
    data class Failure(val reason: String) : AlignmentResult
}
