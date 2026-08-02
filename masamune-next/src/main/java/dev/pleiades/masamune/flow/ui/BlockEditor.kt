package dev.pleiades.masamune.flow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.pleiades.masamune.flow.model.ArgSpec
import dev.pleiades.masamune.flow.model.BlockSpec
import dev.pleiades.masamune.flow.model.FlowNode
import dev.pleiades.masamune.flow.model.OptionSpec
import dev.pleiades.masamune.flow.model.OutSpec
import dev.pleiades.masamune.flow.model.ProceedMode
import dev.pleiades.masamune.ui.components.SectionCard
import dev.pleiades.masamune.ui.theme.MasamuneTheme

/** The key under which the compile-time `Proceed` tense is stored in [FlowNode.options]. */
private const val PROCEED_KEY = "proceed"

/** A plain variable name — an output binds to one of these and nothing else. */
private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/**
 * The three-section block editor, ported straight from Automate's model (donor organ 3):
 *
 *  - **Options** — compile-time enumerated choices, fixed while a fiber runs. `Proceed` lives here.
 *  - **Input arguments** — per-fiber, each with an `fx` toggle switching the field between a
 *    constant and an expression (stored in [FlowNode.argIsExpression]). Optional arguments show
 *    their documented default.
 *  - **Output variables** — a bare variable *name*, never an expression. Anything that is not a
 *    plain identifier is rejected at edit time and never written into the node.
 *
 * The asymmetry — inputs are expressions, outputs are names — is deliberate and enforced here: a
 * field that accepted an expression as an output is one that could not bind a result.
 */
@Composable
fun BlockEditor(
    node: FlowNode,
    spec: BlockSpec,
    onChange: (FlowNode) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(spec.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${spec.category.label} · ${spec.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MasamuneTheme.semantic.dim,
                    fontFamily = FontFamily.Monospace,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete this block",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(spec.summary, style = MaterialTheme.typography.bodyMedium)

        val hasOptions = spec.options.isNotEmpty() || spec.proceedModes.isNotEmpty()
        if (hasOptions) {
            SectionCard(
                title = "Options",
                subtitle = "Compile-time. Fixed when you edit the flow; a running fiber never sees these change.",
            ) {
                if (spec.proceedModes.isNotEmpty()) {
                    ProceedSelector(spec.proceedModes, node, onChange)
                }
                spec.options.forEach { option ->
                    OptionSelector(option, node, onChange)
                }
            }
        }

        if (spec.args.isNotEmpty()) {
            SectionCard(
                title = "Input arguments",
                subtitle = "Per fiber. fx switches a field between a constant and an expression.",
            ) {
                spec.args.forEach { arg ->
                    ArgField(arg, node, onChange)
                }
            }
        }

        if (spec.outputs.isNotEmpty()) {
            SectionCard(
                title = "Output variables",
                subtitle = "A bare variable name — never an expression.",
            ) {
                spec.outputs.forEach { out ->
                    OutField(out, node, onChange)
                }
            }
        }

        SectionCard(title = "Note", subtitle = "Your own comment. Renders on the node.") {
            val current = node.note.orEmpty()
            OutlinedTextField(
                value = current,
                onValueChange = { onChange(node.copy(note = it.ifBlank { null })) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Note") },
                singleLine = false,
            )
        }

        if (!hasOptions && spec.args.isEmpty() && spec.outputs.isEmpty()) {
            Text(
                "This block has no options, inputs or outputs to configure.",
                style = MaterialTheme.typography.bodySmall,
                color = MasamuneTheme.semantic.dim,
            )
        }
    }
}

@Composable
private fun ProceedSelector(
    modes: List<ProceedMode>,
    node: FlowNode,
    onChange: (FlowNode) -> Unit,
) {
    val selected = node.options[PROCEED_KEY] ?: modes.first().name
    Column {
        Text("Proceed", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            modes.forEach { mode ->
                ChoiceChip(
                    label = mode.label,
                    selected = selected == mode.name,
                    onClick = { onChange(node.copy(options = node.options + (PROCEED_KEY to mode.name))) },
                )
            }
        }
    }
}

@Composable
private fun OptionSelector(
    option: OptionSpec,
    node: FlowNode,
    onChange: (FlowNode) -> Unit,
) {
    val selected = node.options[option.key] ?: option.defaultChoice
    Column {
        Text(option.label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            option.choices.forEach { choice ->
                ChoiceChip(
                    label = choice.label,
                    selected = selected == choice.value,
                    onClick = { onChange(node.copy(options = node.options + (option.key to choice.value))) },
                )
            }
        }
    }
}

@Composable
private fun ArgField(
    arg: ArgSpec,
    node: FlowNode,
    onChange: (FlowNode) -> Unit,
) {
    val isExpr = node.argIsExpression[arg.key] ?: false
    var text by remember(node.id, arg.key) { mutableStateOf(node.args[arg.key].orEmpty()) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${arg.label} · ${arg.type.name.lowercase()}",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "fx",
                style = MaterialTheme.typography.labelMedium,
                color = if (isExpr) MaterialTheme.colorScheme.primary else MasamuneTheme.semantic.dim,
                fontFamily = FontFamily.Monospace,
            )
            Switch(
                checked = isExpr,
                onCheckedChange = { on ->
                    onChange(node.copy(argIsExpression = node.argIsExpression + (arg.key to on)))
                },
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onChange(node.copy(args = node.args + (arg.key to it)))
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(if (isExpr) "expression over variables" else "constant value")
            },
            singleLine = !isExpr,
            textStyle = if (isExpr) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            supportingText = {
                val hint = if (arg.optional) {
                    "Optional. " + (arg.defaultBlurb?.let { "Default: $it" } ?: "Unset uses the block's default.")
                } else {
                    "Required."
                }
                Text(hint, style = MaterialTheme.typography.labelSmall)
            },
        )
    }
}

@Composable
private fun OutField(
    out: OutSpec,
    node: FlowNode,
    onChange: (FlowNode) -> Unit,
) {
    var text by remember(node.id, out.key) { mutableStateOf(node.outputs[out.key].orEmpty()) }
    val valid = text.isEmpty() || IDENTIFIER.matches(text)

    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            text = new
            // Reject at edit time: a non-identifier is shown with an error but never committed to
            // the node, so a flow can never carry an output binding it cannot resolve.
            if (new.isEmpty()) {
                onChange(node.copy(outputs = node.outputs - out.key))
            } else if (IDENTIFIER.matches(new)) {
                onChange(node.copy(outputs = node.outputs + (out.key to new)))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        label = { Text(out.label) },
        singleLine = true,
        isError = !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        supportingText = {
            if (!valid) {
                Text(
                    "Not a variable name. Use letters, digits and underscore; may not start with a digit.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (out.blurb != null) {
                Text(out.blurb!!, style = MaterialTheme.typography.labelSmall)
            }
        },
    )
}

/** A small single-select pill. Custom rather than FilterChip to avoid experimental Material3 API. */
@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
