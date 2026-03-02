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
)

enum class ParameterType { SPIN, CHECK, COMBO, STRING }
