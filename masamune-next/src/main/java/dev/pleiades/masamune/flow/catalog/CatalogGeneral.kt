package dev.pleiades.masamune.flow.catalog

import dev.pleiades.masamune.flow.model.BlockCategory
import dev.pleiades.masamune.flow.model.BlockSpec

/**
 * Variables, arrays, dictionaries, iteration and the bare conditional.
 *
 * `Expression true` is the escape hatch that keeps the other 417 blocks from needing one: any
 * condition the palette does not name can be written in the expression language and branched
 * on here. `For each` is the second of the shape-mapped specials - its `DO` dot becomes YES
 * and its `OK` dot NO, which happens to read exactly as the loop's own question.
 *
 * Blocks are listed in Automate's own palette order, which is the order this catalog and the
 * palette both render. See `docs/donors/RE-automate.md`.
 */
internal val GENERAL_BLOCKS: List<BlockSpec> = category(BlockCategory.GENERAL) {
    decision(
        "android_version", "Android version",
        "Checks the Android version running on the device.",
        args = listOf(
            any("minLevel", "Minimum version"),
            any("maxLevel", "Maximum version"),
        ),
        outputs = listOf(
            out("varLevel", "Current version"),
        ),
    )
    action(
        "array_add", "Array add",
        "Inserts a value into an array.",
        args = listOf(
            arr("index", "Index", "end of array"),
            any("value", "Value", "null"),
        ),
    )
    action(
        "array_remove", "Array remove",
        "Removes an element from an array.",
        args = listOf(
            any("index", "Index", "first element, index 0"),
        ),
        outputs = listOf(
            out("varOldValue", "Value removed"),
        ),
    )
    action(
        "array_set", "Array set",
        "Replaces an element in an array.",
        args = listOf(
            arr("index", "Index", "end of array"),
            any("value", "Value", "null"),
        ),
    )
    action(
        "destructuring_assign", "Destructuring assign",
        "Assigns multiple variables with the elements of an array.",
        args = listOf(
            arr("value", "Value", "[]"),
        ),
    )
    action(
        "dictionary_put", "Dictionary put",
        "Associates a value with a key in a dictionary.",
        args = listOf(
            any("key", "Key"),
            any("value", "Value", "null"),
            any("conversionType", "Conversion type", "none"),
        ),
        outputs = listOf(
            out("varOldValue", "Value replaced"),
        ),
    )
    action(
        "dictionary_remove", "Dictionary remove",
        "Removes any value associated with a key from a dictionary.",
        args = listOf(
            any("key", "Key"),
        ),
        outputs = listOf(
            out("varOldValue", "Value removed"),
        ),
    )
    decision(
        "expression_decision", "Expression true",
        "Checks if an expression evaluates to true.",
        args = listOf(
            text("expression", "Expression"),
        ),
    )
    decision(
        "for_each", "For each",
        "Iterates over each element of an array, entry of a dictionary, character of a text, " +
            "or a fixed number of times. Automate gives this block a DO and an OK dot, which " +
            "reads naturally as a decision: YES is the loop body for one more element, NO is the " +
            "exhausted iterator.",
        args = listOf(
            arr("container", "Container"),
        ),
        outputs = listOf(
            out("varEntryValue", "Entry value"),
            out("varEntryIndex", "Entry index"),
            out("varEntryKey", "Dictionary key"),
        ),
    )
    action(
        "variable_assign", "Variable set",
        "Assigns a value to a variable.",
        args = listOf(
            any("value", "Value", "null"),
        ),
    )
}
