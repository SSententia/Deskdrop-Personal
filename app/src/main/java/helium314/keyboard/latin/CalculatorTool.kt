// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import helium314.keyboard.latin.utils.showImeComposeDialog
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.screens.brandTeal
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Serializable
data class CustomMathFunction(
    val name: String,
    val params: List<String>,
    val expression: String
)

private val defaultFunctions = listOf(
    CustomMathFunction(
        name = "Pythagoras",
        params = listOf("a", "b"),
        expression = "sqrt(a^2 + b^2)"
    )
)

fun loadCustomFunctions(context: Context): List<CustomMathFunction> {
    val prefs = DeviceProtectedUtils.getSharedPreferences(context)
    val jsonStr = prefs.getString("custom_math_functions", null) ?: return defaultFunctions
    return try {
        Json.decodeFromString<List<CustomMathFunction>>(jsonStr)
    } catch (e: Exception) {
        defaultFunctions
    }
}

fun saveCustomFunctions(context: Context, list: List<CustomMathFunction>) {
    val prefs = DeviceProtectedUtils.getSharedPreferences(context)
    val jsonStr = Json.encodeToString(list)
    prefs.edit().putString("custom_math_functions", jsonStr).apply()
}

class MathParser(private val expr: String, val variables: Map<String, Double> = emptyMap()) {
    private var pos = -1
    private var ch = 0

    private fun nextChar() {
        ch = if (++pos < expr.length) expr[pos].code else -1
    }

    private fun eat(charToEat: Int): Boolean {
        while (ch == ' '.code) nextChar()
        if (ch == charToEat) {
            nextChar()
            return true
        }
        return false
    }

    fun parse(): Double {
        nextChar()
        val x = parseExpression()
        if (pos < expr.length) {
            while (ch == ' '.code) nextChar()
            if (pos < expr.length) throw RuntimeException("Unexpected character: " + ch.toChar())
        }
        return x
    }

    private fun parseExpression(): Double {
        var x = parseTerm()
        while (true) {
            if (eat('+'.code)) x += parseTerm()
            else if (eat('-'.code)) x -= parseTerm()
            else return x
        }
    }

    private fun parseTerm(): Double {
        var x = parseFactor()
        while (true) {
            if (eat('*'.code)) {
                x *= parseFactor()
            } else if (eat('/'.code)) {
                x /= parseFactor()
            } else if (eat('%'.code)) {
                x %= parseFactor()
            } else {
                val nextCh = peekNextNonWhitespaceChar()
                if (nextCh == '('.code || (nextCh >= '0'.code && nextCh <= '9'.code) || nextCh == '.'.code ||
                    (nextCh >= 'a'.code && nextCh <= 'z'.code) || (nextCh >= 'A'.code && nextCh <= 'Z'.code) ||
                    nextCh == 'π'.code || nextCh == '√'.code || nextCh == '_'.code) {
                    x *= parseFactor()
                } else {
                    return x
                }
            }
        }
    }

    private fun peekNextNonWhitespaceChar(): Int {
        if (ch != ' '.code) return ch
        var p = pos
        while (p < expr.length) {
            val c = expr[p]
            if (c != ' ') return c.code
            p++
        }
        return -1
    }

    private fun parseFactor(): Double {
        if (eat('+'.code)) return parseFactor()
        if (eat('-'.code)) return -parseFactor()

        var x: Double
        val startPos = this.pos
        if (eat('('.code)) {
            x = parseExpression()
            eat(')'.code)
        } else if ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) {
            while ((ch >= '0'.code && ch <= '9'.code) || ch == '.'.code) nextChar()
            x = expr.substring(startPos, this.pos).toDouble()
        } else if ((ch >= 'a'.code && ch <= 'z'.code) || (ch >= 'A'.code && ch <= 'Z'.code) || ch == 'π'.code || ch == '√'.code || ch == '_'.code) {
            if (ch == 'π'.code) {
                x = Math.PI
                nextChar()
            } else if (ch == '√'.code) {
                nextChar()
                x = parseFactor()
                x = kotlin.math.sqrt(x)
            } else {
                while ((ch >= 'a'.code && ch <= 'z'.code) || (ch >= 'A'.code && ch <= 'Z'.code) || (ch >= '0'.code && ch <= '9'.code) || ch == '_'.code) nextChar()
                val name = expr.substring(startPos, this.pos)
                if (name == "pi") {
                    x = Math.PI
                } else if (name == "e") {
                    x = Math.E
                } else if (variables.containsKey(name)) {
                    x = variables[name]!!
                } else {
                    val arg = parseFactor()
                    x = when (name) {
                        "sqrt" -> kotlin.math.sqrt(arg)
                        "sin" -> kotlin.math.sin(arg)
                        "cos" -> kotlin.math.cos(arg)
                        "tan" -> kotlin.math.tan(arg)
                        "log" -> kotlin.math.log10(arg)
                        "ln" -> kotlin.math.ln(arg)
                        "exp" -> kotlin.math.exp(arg)
                        "abs" -> kotlin.math.abs(arg)
                        else -> throw RuntimeException("Unknown function: $name")
                    }
                }
            }
        } else {
            throw RuntimeException("Unexpected character: " + ch.toChar())
        }

        while (true) {
            if (eat('^'.code)) {
                x = Math.pow(x, parseFactor())
            } else if (eat('!'.code)) {
                x = factorial(x)
            } else {
                break
            }
        }

        return x
    }

    private fun factorial(n: Double): Double {
        if (n < 0.0) return Double.NaN
        val intVal = n.toInt()
        if (intVal.toDouble() != n) return Double.NaN
        if (intVal > 170) return Double.POSITIVE_INFINITY
        var res = 1.0
        for (i in 2..intVal) {
            res *= i
        }
        return res
    }
}

fun evaluateExpression(expr: String, variables: Map<String, Double> = emptyMap()): Double {
    if (expr.trim().isEmpty()) return Double.NaN
    return try {
        MathParser(expr, variables).parse()
    } catch (e: Exception) {
        Double.NaN
    }
}

fun formatResult(value: Double): String {
    if (value.isNaN()) return ""
    if (value.isInfinite()) return "Infinity"
    val longVal = value.toLong()
    if (value == longVal.toDouble()) {
        return longVal.toString()
    }
    val str = String.format(java.util.Locale.US, "%.8f", value)
    var trimmed = str
    if (trimmed.contains(".")) {
        while (trimmed.endsWith("0")) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
        if (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
    }
    return trimmed
}

fun validateFunction(name: String, params: String, expr: String): String? {
    if (name.trim().isEmpty()) return "Name cannot be empty"
    if (!name.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*"))) return "Invalid function name (must start with letter)"
    
    val paramList = params.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    for (p in paramList) {
        if (!p.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*"))) return "Invalid parameter name: $p"
    }
    
    if (expr.trim().isEmpty()) return "Expression cannot be empty"
    
    val mockVars = paramList.associateWith { 1.0 }
    try {
        MathParser(expr, mockVars).parse()
    } catch (e: Exception) {
        return "Syntax Error: ${e.message}"
    }
    
    return null
}

enum class CalculatorScreen {
    CALCULATOR,
    FUNCTIONS_LIST,
    RUN_FUNCTION,
    CREATE_FUNCTION
}

fun showCalculatorTool(ime: LatinIME) {
    showImeComposeDialog(
        ime = ime,
        chromeless = true,
        focusable = true,
        onDismiss = {
            ime.setDialogEditText(null)
        },
        content = {
            CalculatorContent(ime)
        }
    )
}

@Composable
fun CalculatorContent(ime: LatinIME) {
    val context = LocalContext.current
    var currentScreen by remember { mutableStateOf(CalculatorScreen.CALCULATOR) }
    var customFunctions by remember { mutableStateOf(loadCustomFunctions(context)) }
    var selectedFunction by remember { mutableStateOf<CustomMathFunction?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(380.dp),
        color = Color(0xFF1A1A1A),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        when (currentScreen) {
            CalculatorScreen.CALCULATOR -> {
                MainCalcScreen(
                    ime = ime,
                    onOpenFunctions = { currentScreen = CalculatorScreen.FUNCTIONS_LIST }
                )
            }
            CalculatorScreen.FUNCTIONS_LIST -> {
                FunctionsListScreen(
                    customFunctions = customFunctions,
                    onBack = { currentScreen = CalculatorScreen.CALCULATOR },
                    onRunFunction = { fn ->
                        selectedFunction = fn
                        currentScreen = CalculatorScreen.RUN_FUNCTION
                    },
                    onDeleteFunction = { fn ->
                        val updated = customFunctions.filterNot { it.name == fn.name }
                        customFunctions = updated
                        saveCustomFunctions(context, updated)
                    },
                    onCreateNew = { currentScreen = CalculatorScreen.CREATE_FUNCTION }
                )
            }
            CalculatorScreen.RUN_FUNCTION -> {
                selectedFunction?.let { fn ->
                    RunFunctionScreen(
                        ime = ime,
                        function = fn,
                        onBack = { currentScreen = CalculatorScreen.FUNCTIONS_LIST }
                    )
                }
            }
            CalculatorScreen.CREATE_FUNCTION -> {
                CreateFunctionScreen(
                    onBack = { currentScreen = CalculatorScreen.FUNCTIONS_LIST },
                    onSave = { newFn ->
                        val updated = customFunctions + newFn
                        customFunctions = updated
                        saveCustomFunctions(context, updated)
                        currentScreen = CalculatorScreen.FUNCTIONS_LIST
                    }
                )
            }
        }
    }
}

@Composable
fun MainCalcScreen(
    ime: LatinIME,
    onOpenFunctions: () -> Unit
) {
    var exprInput by remember { mutableStateOf("") }
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    val evaluated = remember(exprInput) { evaluateExpression(exprInput) }
    val resultStr = remember(evaluated) { formatResult(evaluated) }

    fun appendText(txt: String) {
        editTextRef?.let { et ->
            val start = et.selectionStart
            val end = et.selectionEnd
            if (start < 0 || end < 0) {
                et.append(txt)
            } else {
                et.text.replace(Math.min(start, end), Math.max(start, end), txt)
            }
            exprInput = et.text.toString()
        }
    }

    fun deleteChar() {
        editTextRef?.let { et ->
            val start = et.selectionStart
            val end = et.selectionEnd
            if (start == end) {
                if (start > 0) {
                    et.text.delete(start - 1, start)
                }
            } else {
                et.text.delete(Math.min(start, end), Math.max(start, end))
            }
            exprInput = et.text.toString()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Calculator",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenFunctions) {
                Text(
                    text = "f(x)",
                    color = brandTeal(),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(onClick = { ime.getActiveDialog()?.dismiss() }) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_rounded),
                    contentDescription = "Close",
                    tint = Color.White
                )
            }
        }

        // Display area — phone calculator style: result above input
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF252525), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Result preview area — bumped up slightly to 22.dp to prevent clipping
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (resultStr.isNotEmpty()) {
                    Text(
                        text = resultStr,
                        color = Color.Gray,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 2.dp),
                        textAlign = TextAlign.End
                    )
                }
            }
            
            // Expression input
            AndroidView(
                factory = { ctx ->
                    EditText(ctx).apply {
                        editTextRef = this
                        hint = "0"
                        setHintTextColor(AndroidColor.argb(100, 255, 255, 255))
                        background = null
                        
                        // FIX: Explicitly remove native padding and font padding overrides
                        setPadding(0, 0, 0, 0)
                        setIncludeFontPadding(false)
                        
                        setTextColor(AndroidColor.WHITE)
                        textSize = 20f
                        setSingleLine(true)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                        setOnFocusChangeListener { v, hasFocus ->
                            if (hasFocus) {
                                ime.setDialogEditText(v as EditText)
                            }
                        }
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                exprInput = s?.toString() ?: ""
                            }
                            override fun afterTextChanged(s: android.text.Editable?) {}
                        })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                update = { et ->
                    if (et.text.toString() != exprInput) {
                        et.setText(exprInput)
                        et.setSelection(exprInput.length)
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Keyboard grid
        val keys = listOf(
            listOf("C", "(", ")", "⌫"),
            listOf("sin", "cos", "tan", "^"),
            listOf("√", "!", "ln", "/"),
            listOf("7", "8", "9", "*"),
            listOf("4", "5", "6", "-"),
            listOf("1", "2", "3", "+"),
            listOf("0", ".", "=", "Insert")
        )

        Column(modifier = Modifier.fillMaxWidth()) {
            keys.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.forEach { key ->
                        val isNum = key.firstOrNull()?.isDigit() == true || key == "."
                        val isAction = key == "C" || key == "⌫" || key == "=" || key == "Insert"
                        val bg = when {
                            key == "Insert" || key == "=" -> brandTeal()
                            isAction -> Color(0xFF333333)
                            isNum -> Color(0xFF2A2A2A)
                            else -> Color(0xFF222222)
                        }
                        val fg = when {
                            key == "Insert" || key == "=" -> Color.Black
                            else -> Color.White
                        }

                        CalcButton(
                            text = key,
                            onClick = {
                                when (key) {
                                    "C" -> {
                                        exprInput = ""
                                        editTextRef?.setText("")
                                    }
                                    "⌫" -> deleteChar()
                                    "=" -> {
                                        if (resultStr.isNotEmpty() && resultStr != "Infinity" && resultStr != "NaN") {
                                            exprInput = resultStr
                                            editTextRef?.setText(resultStr)
                                            editTextRef?.setSelection(resultStr.length)
                                            // keep focus on the calculator input so user can continue calculations
                                            editTextRef?.requestFocus()
                                            editTextRef?.let { ime.setDialogEditText(it) }
                                        }
                                    }
                                    "Insert" -> {
                                        if (resultStr.isNotEmpty() && resultStr != "Infinity" && resultStr != "NaN") {
                                            ime.currentInputConnection?.commitText(resultStr, 1)
                                            ime.getActiveDialog()?.dismiss()
                                        }
                                    }
                                    else -> appendText(key)
                                }
                            },
                            backgroundColor = bg,
                            contentColor = fg,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CalcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    contentColor: Color
) {
    Box(
        modifier = modifier
            .padding(1.dp)
            .height(34.dp)
            .background(backgroundColor, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun FunctionsListScreen(
    customFunctions: List<CustomMathFunction>,
    onBack: () -> Unit,
    onRunFunction: (CustomMathFunction) -> Unit,
    onDeleteFunction: (CustomMathFunction) -> Unit,
    onCreateNew: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left_rounded),
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Functions",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onCreateNew,
                colors = ButtonDefaults.buttonColors(containerColor = brandTeal()),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("+ New", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(customFunctions) { fn ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF252525))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${fn.name}(${fn.params.joinToString(", ")})",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "= ${fn.expression}",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(onClick = { onRunFunction(fn) }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_overlay_execute),
                                contentDescription = "Run",
                                tint = brandTeal()
                            )
                        }
                        if (fn.name != "Pythagoras") {
                            IconButton(onClick = { onDeleteFunction(fn) }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bin_rounded),
                                    contentDescription = "Delete",
                                    tint = Color(0xFFE53935)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RunFunctionScreen(
    ime: LatinIME,
    function: CustomMathFunction,
    onBack: () -> Unit
) {
    val varValues = remember { mutableStateMapOf<String, String>() }
    val parsedVars = remember(varValues.toMap()) {
        function.params.associateWith { paramName ->
            varValues[paramName]?.toDoubleOrNull() ?: Double.NaN
        }
    }
    val result = remember(parsedVars) { evaluateExpression(function.expression, parsedVars) }
    val formattedResult = remember(result) { formatResult(result) }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left_rounded),
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "Run: ${function.name}",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            text = "Formula: ${function.name}(${function.params.joinToString(", ")}) = ${function.expression}",
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Inputs list
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            function.params.forEach { paramName ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "$paramName = ",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(60.dp)
                    )
                    AndroidView(
                        factory = { ctx ->
                            EditText(ctx).apply {
                                hint = "Enter value"
                                setHintTextColor(AndroidColor.argb(100, 255, 255, 255))
                                setSingleLine(true)
                                setTextColor(AndroidColor.WHITE)
                                setBackgroundDrawable(GradientDrawable().apply {
                                    setColor(AndroidColor.argb(30, 255, 255, 255))
                                    setStroke(1, AndroidColor.argb(80, 255, 255, 255))
                                    cornerRadius = 8f
                                })
                                setPadding(16, 12, 16, 12)
                                setOnFocusChangeListener { v, hasFocus ->
                                    if (hasFocus) {
                                        ime.setDialogEditText(v as EditText)
                                    }
                                }
                                addTextChangedListener(object : android.text.TextWatcher {
                                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                        varValues[paramName] = s?.toString() ?: ""
                                    }
                                    override fun afterTextChanged(s: android.text.Editable?) {}
                                })
                            }
                        },
                        modifier = Modifier.weight(1f).height(40.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (formattedResult.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF252525))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(text = "Result", color = Color.Gray, fontSize = 12.sp)
                        Text(
                            text = formattedResult,
                            color = brandTeal(),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                ime.currentInputConnection?.commitText(formattedResult, 1)
                                ime.getActiveDialog()?.dismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = brandTeal()),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Insert Result", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CreateFunctionScreen(
    onBack: () -> Unit,
    onSave: (CustomMathFunction) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var params by remember { mutableStateOf("") }
    var expression by remember { mutableStateOf("") }

    val validationError = remember(name, params, expression) {
        validateFunction(name, params, expression)
    }

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left_rounded),
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Text(
                text = "New Function",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    if (validationError == null) {
                        val paramList = params.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        onSave(CustomMathFunction(name.trim(), paramList, expression.trim()))
                    }
                },
                enabled = validationError == null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = brandTeal(),
                    disabledContainerColor = Color.Gray.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Save", color = if (validationError == null) Color.Black else Color.DarkGray, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Function Name Field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Function Name (e.g. area)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = brandTeal(),
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = brandTeal(),
                    unfocusedLabelColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Parameters Field
            OutlinedTextField(
                value = params,
                onValueChange = { params = it },
                label = { Text("Parameters (comma separated, e.g. w, h)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = brandTeal(),
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = brandTeal(),
                    unfocusedLabelColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Expression Field
            OutlinedTextField(
                value = expression,
                onValueChange = { expression = it },
                label = { Text("Expression (e.g. w * h)") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = brandTeal(),
                    unfocusedBorderColor = Color.Gray,
                    focusedLabelColor = brandTeal(),
                    unfocusedLabelColor = Color.Gray,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (validationError != null) {
                Text(
                    text = validationError,
                    color = Color(0xFFE53935),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            } else {
                Text(
                    text = "✓ Expression compiles successfully!",
                    color = brandTeal(),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}
