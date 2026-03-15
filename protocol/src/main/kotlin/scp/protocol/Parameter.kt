package scp.protocol

data class ParameterDescriptor(
    val name: String,
    val type: ParameterType,
    val description: String,
    val default: Any? = null,
    val required: Boolean = false,
    val sensitive: Boolean = false,
    val min: Int? = null,
    val max: Int? = null,
    val options: List<String>? = null,
    val visibleWhen: VisibleWhen? = null,
)

/**
 * Condition under which a parameter is visible. When not visible, UIs should
 * hide the parameter and must not require the user to provide a value.
 *
 * Exactly one of [equals] or [oneOf] must be set.
 *
 * @param field Name of another parameter in the same array.
 * @param equals The parameter is visible when [field]'s value equals this value.
 * @param oneOf The parameter is visible when [field]'s value is one of these values.
 */
data class VisibleWhen(
    val field: String,
    val equals: Any? = null,
    val oneOf: List<Any>? = null,
)

enum class ParameterType { SPIN, CHECK, COMBO, STRING }
