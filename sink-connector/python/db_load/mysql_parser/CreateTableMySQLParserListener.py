from antlr4 import *
from db_load.mysql_parser.MySqlParserListener import MySqlParserListener
from db_load.mysql_parser.MySqlParser import MySqlParser
from db.mysql import is_binary_datatype
import re
import logging

# MySQL BIT(1), also written BIT (the length defaults to 1).
BIT1_DATATYPE = re.compile(r"^\s*bit\s*(\(\s*1\s*\))?\s*$", re.IGNORECASE)


class UnsafeTableDefinitionError(ValueError):
    """The translator refuses a table it cannot create faithfully (Spec 13.04 sections 3.11, 3.12).

    Raised instead of emitting DDL that would lose or misplace MySQL data. The loader re-raises it rather than
    falling back to the regexp translator, so the run fails loudly with this message."""


# Generated-column expressions (Spec 13.04 section 3.16). The MySQL expression is passed to ClickHouse as written,
# except for the constructs ClickHouse lacks or evaluates differently: the bit operators, which are translated, and
# charset introducers (_utf8mb4'x'), which are dropped. Kinds of operand, for the bit operators only: an integer
# (integer columns and literals, bit results, comparisons and logical operations), a known non-integer (a column
# of any other type, a decimal, string, hexadecimal or bit literal), or unknown (anything else, passed through).
KIND_INTEGER = "integer"
KIND_OTHER = "non-integer"
KIND_UNKNOWN = "unknown"

INTEGER_DATATYPE = re.compile(r"^\s*((tiny|small|medium|big|middle)?int(eger)?|int[1-8]|bool(ean)?)\b", re.IGNORECASE)

BIT_OPERATORS = ("&", "|", "^", "<<", ">>")
# MySQL precedence of the binary bit and arithmetic operators, highest first (MySQL 8.0 reference manual,
# "Operator Precedence"); all are left-associative. The grammar puts every bitOperator alternative before every
# mathOperator alternative with one precedence level each, so a chain that holds a bit operator is regrouped here.
ATOM_OPERATOR_PRECEDENCE = {"^": 6, "*": 5, "/": 5, "%": 5, "DIV": 5, "MOD": 5, "+": 4, "-": 4, "<<": 3, ">>": 3,
                            "&": 2, "|": 1}
BIT_FUNCTIONS = {"&": "bitAnd", "|": "bitOr", "^": "bitXor"}
SHIFT_FUNCTIONS = {"<<": "bitShiftLeft", ">>": "bitShiftRight"}


DECIMAL_DATATYPE = re.compile(r"^\s*(decimal|dec|numeric|fixed)\b\s*(\(\s*\d+\s*(,\s*(\d+)\s*)?\))?", re.IGNORECASE)
FLOAT_DATATYPE = re.compile(r"^\s*(float|double|real)\b", re.IGNORECASE)
BINARY_DATATYPE = re.compile(r"^\s*(bit|binary|varbinary|tinyblob|blob|mediumblob|longblob)\b", re.IGNORECASE)
TEMPORAL_DATATYPE = re.compile(r"^\s*(date|datetime|timestamp|year)\b", re.IGNORECASE)
STRING_DATATYPE = re.compile(r"^\s*(char|varchar|tinytext|text|mediumtext|longtext|nchar|nvarchar|national)\b",
                             re.IGNORECASE)


def column_kind(mysql_datatype_text):
    """KIND_INTEGER for a MySQL integer type, KIND_OTHER for any other type."""
    return KIND_INTEGER if INTEGER_DATATYPE.match(mysql_datatype_text) else KIND_OTHER


class UntranslatableExpression(Exception):
    """A generated-column expression ClickHouse would evaluate silently differently from MySQL."""


class GeneratedExpressionTranslator:
    """Render a MySQL generated-column expression (an ANTLR ``expression`` tree) for a ClickHouse DEFAULT clause.

    The text is kept as written (token by token, with the original spacing), so every expression ClickHouse
    already accepts is unchanged, except:

    - charset introducers before string literals (``_utf8mb4'x'``) are dropped, ClickHouse has none;
    - the bit operators ``& | ^ << >>`` and ``~``, which ClickHouse does not have, become bitAnd, bitOr, bitXor,
      bitShiftLeft, bitShiftRight and bitNot on toUInt64 operands (MySQL computes them on unsigned 64-bit
      integers). A shift by 64 or more gives 0 as in MySQL (ClickHouse's own result there depends on the code
      path). A chain holding a bit operator is regrouped by MySQL precedence, because the grammar binds every bit
      operator tighter than every arithmetic operator (``a + b & c`` is ``(a + b) & c`` in MySQL).

    A bit operator on a known non-integer operand (a decimal, floating-point, string, binary or temporal column,
    or such a literal) raises UntranslatableExpression: MySQL rounds 2.5 to 3 before ``&`` where toUInt64
    truncates to 2, and evaluates binary strings bytewise. Such an expression never loaded before (ClickHouse
    rejects ``&``), so the refusal breaks nothing."""

    def __init__(self, column_types, input_stream, table_name="", column_name=""):
        """``column_types`` maps each lower-cased column name of the table to its MySQL data type text;
        ``table_name`` and ``column_name`` name the generated column in WARNINGs."""
        self.column_types = column_types
        self.column_kinds = {name: column_kind(datatype) for (name, datatype) in column_types.items()}
        self.input_stream = input_stream
        self.table_name = table_name
        self.column_name = column_name

    def translate(self, expression, text_context=False, decimal_scale=None):
        """The ClickHouse expression; ``text_context`` when the generated column has a string type, so MySQL
        stores the text of a numeric result; ``decimal_scale`` when it is a DECIMAL(p, s) column, so MySQL stores
        the exact decimal value rounded half away from zero to s digits. ClickHouse would compute ``/`` as a
        Float64 and cut the extra digits on the cast to the column (2/3 into decimal(10,4): 0.6666, MySQL 0.6667), so a
        DECIMAL-valued expression is computed exactly (``value``) and rounded to the column's scale."""
        if text_context:
            return self.text_value(expression)[0]
        if decimal_scale is not None:
            value = self.value(expression)
            if value is not None and value[1] == "dec":
                return f"round({value[0]}, {decimal_scale})"
        return self.render(expression)

    def warn(self, divergence):
        logging.warning(f"Table {self.table_name}: generated column {self.column_name}: {divergence}. The column is "
                        f"created as before (such expressions already loaded); its values may differ from MySQL.")

    # --- rendering --------------------------------------------------------------------------------------
    def render(self, node):
        if isinstance(node, TerminalNode):
            token = node.getSymbol()
            return "" if token.type == MySqlParser.STRING_CHARSET_NAME else token.text
        if isinstance(node, (MySqlParser.BitExpressionAtomContext, MySqlParser.MathExpressionAtomContext)):
            return self.chain(node)[0]
        if self.is_bit_not(node):
            return self.operand(node)[0]
        if isinstance(node, MySqlParser.DataTypeFunctionCallContext) and node.USING() is not None:
            return self.convert_using(node)
        name = self.function_name(node)
        if name in ("IFNULL", "COALESCE"):
            return self.null_function(node)
        if name == "CONCAT":
            return self.concat(node)
        if name == "CONCAT_WS":
            return self.concat_ws(node)
        return self.verbatim(node)

    # --- numbers written as text (CONCAT arguments, string-typed generated columns) --------------------------
    def text_value(self, node):
        """(text, changed) of a value MySQL turns into text. A DECIMAL-valued expression (decimal columns and
        literals, + - * / over them and integers) is rendered with MySQL's scale, ClickHouse's toString would drop
        trailing zeros (1.50 -> 1.5) and its / is a Float64 quotient (3/2 -> 1.5 where MySQL writes 1.5000). Other
        values are rendered as usual; a floating-point or BIT(1) column, or a quotient the loader cannot model,
        loaded before and still does, with a WARNING naming the divergence."""
        value = self.value(node)
        if value is not None and value[1] == "dec":
            return (f"toDecimalString({value[0]}, {value[2]})", True)
        datatype = self.argument_type(node) if not isinstance(node, MySqlParser.ExpressionAtomContext) else None
        if datatype is not None and datatype != "string literal" and FLOAT_DATATYPE.match(datatype):
            self.warn("a FLOAT/DOUBLE column written as text (ClickHouse writes 1e20 as 100000000000000000000, "
                      "MySQL as 1e20)")
        elif datatype is not None and datatype != "string literal" and BIT1_DATATYPE.match(datatype):
            self.warn("a BIT(1) column written as text (ClickHouse writes true/false, MySQL the bit byte)")
        elif value is None and self.own_division(node):
            self.warn("a quotient written as text that the loader cannot model exactly (ClickHouse writes the "
                      "Float64 quotient, MySQL a decimal with the dividend's scale plus 4 digits)")
        return (self.render(node), False)

    def concat(self, node):
        arguments = self.function_arguments(node)
        pieces = [self.text_value(argument) for argument in arguments]
        if not any(changed for (_, changed) in pieces):
            return self.verbatim(node)
        return f"{node.getChild(0).getText()}({','.join(text for (text, _) in pieces)})"

    def concat_ws(self, node):
        """CONCAT_WS(sep, v, ...): MySQL skips NULL values, ClickHouse's concat_ws returns NULL when one is NULL.
        With a string-literal separator it becomes arrayStringConcat over the non-NULL texts; any other separator
        is passed through (ClickHouse accepts only a constant separator, so such a CREATE never loaded and still
        fails loudly)."""
        arguments = self.function_arguments(node)
        if len(arguments) < 2 or self.argument_type(arguments[0]) != "string literal":
            return self.verbatim(node)
        values = []
        for argument in arguments[1:]:
            (text, changed) = self.text_value(argument)
            values.append(text if changed else f"toString({text})")
        return (f"arrayStringConcat(arrayFilter(__v -> __v IS NOT NULL, [{', '.join(values)}]), "
                f"{self.render(arguments[0])})")

    @staticmethod
    def function_arguments(node):
        if node.functionArgs() is None:
            return []
        return [child for child in node.functionArgs().getChildren() if not isinstance(child, TerminalNode)]

    @staticmethod
    def unwrap(node):
        while True:
            if isinstance(node, MySqlParser.PredicateExpressionContext):
                node = node.predicate()
            elif isinstance(node, MySqlParser.ExpressionAtomPredicateContext) and node.LOCAL_ID() is None:
                node = node.expressionAtom()
            elif isinstance(node, MySqlParser.NestedExpressionAtomContext) and len(node.expression()) == 1:
                node = node.expression(0)
            else:
                return node

    def own_division(self, node):
        node = self.unwrap(node)
        if not isinstance(node, (MySqlParser.BitExpressionAtomContext, MySqlParser.MathExpressionAtomContext)):
            return False
        items = self.flatten(node)
        return any(items[i].getText() == "/" for i in range(1, len(items), 2))

    def value(self, node):
        """(ClickHouse expression, 'int' or 'dec', scale) computing exactly MySQL's value, for integer and
        decimal columns and literals combined with + - * / (MySQL: + - keep the larger scale, * adds the scales,
        / gives the dividend's scale plus div_precision_increment 4, at most 30, rounded half away from zero, NULL
        on a zero divisor); None for anything else."""
        node = self.unwrap(node)
        if isinstance(node, (MySqlParser.FullColumnNameContext, MySqlParser.FullColumnNameExpressionAtomContext)):
            return self.column_value(node.getText().strip("`"))
        constant = node.constant() if isinstance(node, MySqlParser.ConstantExpressionAtomContext) else \
            node if isinstance(node, MySqlParser.ConstantContext) else None
        if constant is not None:
            return self.literal_value(constant)
        if isinstance(node, MySqlParser.UnaryExpressionAtomContext) and node.unaryOperator().getText() in ("-", "+"):
            inner = self.value(node.expressionAtom())
            if inner is None:
                return None
            return inner if node.unaryOperator().getText() == "+" else (f"(-{inner[0]})", inner[1], inner[2])
        if isinstance(node, MySqlParser.MathExpressionAtomContext):
            items = self.flatten(node)
            operators = [items[i].getText().upper() for i in range(1, len(items), 2)]
            if any(operator not in ("+", "-", "*", "/") for operator in operators):
                return None
            operands = [self.value(items[i]) for i in range(0, len(items), 2)]
            if any(operand is None for operand in operands):
                return None
            sequence = [operands[i // 2] if i % 2 == 0 else (items[i], None) for i in range(len(items))]
            return self.regroup(sequence, self.combine_value)
        return None

    def column_value(self, name):
        datatype = self.column_types.get(name.replace("``", "`").lower())
        if datatype is None:
            return None
        if INTEGER_DATATYPE.match(datatype) and not BIT1_DATATYPE.match(datatype):
            return (f"`{name}`", "int", 0)
        decimal = DECIMAL_DATATYPE.match(datatype)
        if decimal:
            return (f"`{name}`", "dec", int(decimal.group(4) or 0))
        return None

    def literal_value(self, ctx):
        string = ctx.stringLiteral()
        if string is not None:
            if string.getChildCount() == 1 and string.getText().startswith("`"):
                return self.column_value(string.getText()[1:-1])
            return None
        literal = ctx.decimalLiteral()
        if literal is None:
            return None
        text = literal.getText()
        sign = "-" if ctx.getChildCount() == 2 else ""
        if literal.REAL_LITERAL() is None:
            return (f"({sign}{text})" if sign else text, "int", 0)
        if re.fullmatch(r"\d*\.\d*", text):
            scale = len(text.split(".")[1])
            return (f"toDecimal256('{sign}{text}', {scale})", "dec", scale)
        return None  # exponent: a DOUBLE in MySQL

    def combine_value(self, left, operator_node, right):
        operator = operator_node.getText()
        (lt, lk, ls) = left
        (rt, rk, rs) = right
        if operator in ("+", "-", "*") and lk == rk == "int":
            return (f"({lt} {operator} {rt})", "int", 0)
        (a, b) = (f"toDecimal256({lt}, {ls})", f"toDecimal256({rt}, {rs})")
        if operator in ("+", "-"):
            return (f"({a} {operator} {b})", "dec", max(ls, rs))
        if operator == "*":
            return (f"({a} * {b})", "dec", ls + rs)
        scale = min(ls + 4, 30)
        # divideDecimal also runs on the rows if() discards (and on the default value under a NULL), so the
        # divisor it sees is never 0; the result is NULL for a zero or NULL divisor, as in MySQL.
        safe = f"coalesce(nullIf({b}, 0), toDecimal256(1, {rs}))"
        return (f"if(ifNull({b} = 0, 1), NULL, toDecimal256(round(divideDecimal({a}, {safe}, {scale + 1}), {scale}), "
                f"{scale}))", "dec", scale)

    @staticmethod
    def function_name(node):
        if isinstance(node, MySqlParser.ScalarFunctionCallContext):
            return node.scalarFunctionName().getText().upper()
        if isinstance(node, MySqlParser.UdfFunctionCallContext):
            return node.fullId().getText().upper()
        return None

    def null_function(self, node):
        """IFNULL / COALESCE over a string and a column of another type. MySQL returns a string (the column's
        text when it is not NULL); ClickHouse has no common type for String and a number or date and rejects
        the CREATE. Such a column argument is rendered as text, as the streaming connector does (toString; a
        decimal with its declared scale, toString would drop its trailing zeros); a floating-point column
        (MySQL writes 1e20, toString 100000000000000000000) or a BIT(1) column (loaded as Bool: 'true' against
        MySQL's raw byte) is refused. These combinations never loaded before. Arguments of the same kind, or of
        unknown type, are passed through: what ClickHouse accepted before is unchanged."""
        arguments = [child for child in node.functionArgs().getChildren() if not isinstance(child, TerminalNode)] \
            if node.functionArgs() is not None else []
        datatypes = [self.argument_type(argument) for argument in arguments]
        categories = [self.category(datatype) for datatype in datatypes]
        if "string" not in categories or not any(c in ("integer", "decimal", "temporal", "float", "bool")
                                                for c in categories):
            return self.verbatim(node)
        rendered = []
        for (argument, datatype, category) in zip(arguments, datatypes, categories):
            text = self.render(argument)
            if category in ("integer", "temporal"):
                text = f"toString({text})"
            elif category == "decimal":
                text = f"toDecimalString({text}, {DECIMAL_DATATYPE.match(datatype).group(4) or 0})"
            elif category in ("float", "bool"):
                raise UntranslatableExpression(f"{self.function_name(node)} of a string and a "
                                               f"{datatype.split('(')[0].strip()} column")
            rendered.append(text)
        name = node.getChild(0).getText()
        return f"{name}({','.join(rendered)})"

    def argument_type(self, argument):
        """The MySQL data type of a function argument that is a column, 'string' for a string literal, else None."""
        if isinstance(argument, MySqlParser.FullColumnNameContext):
            return self.column_types.get(argument.getText().strip("`").lower())
        if isinstance(argument, MySqlParser.ConstantContext):
            string = argument.stringLiteral()
            if string is None:
                return None
            if string.getChildCount() == 1 and string.getText().startswith("`"):
                return self.column_types.get(string.getText()[1:-1].replace("``", "`").lower())
            return "string literal"
        if isinstance(argument, MySqlParser.ExpressionContext):
            datatype = self.direct_column_type(argument)
            if datatype is not None:
                return datatype
            atom = argument
            while isinstance(atom, (MySqlParser.PredicateExpressionContext, MySqlParser.ExpressionAtomPredicateContext)):
                atom = atom.predicate() if isinstance(atom, MySqlParser.PredicateExpressionContext) else atom.expressionAtom()
            if isinstance(atom, MySqlParser.ConstantExpressionAtomContext) and atom.constant().stringLiteral() is not None:
                return "string literal"
        return None

    @staticmethod
    def category(datatype):
        """'string', 'integer', 'decimal', 'float', 'binary', 'temporal' or None (unknown) for a MySQL data type."""
        if datatype is None:
            return None
        if datatype == "string literal":
            return "string"
        if INTEGER_DATATYPE.match(datatype):
            return "integer"
        if DECIMAL_DATATYPE.match(datatype):
            return "decimal"
        if FLOAT_DATATYPE.match(datatype):
            return "float"
        if BIT1_DATATYPE.match(datatype):
            return "bool"  # loaded as Bool: no common type with String in ClickHouse
        if BINARY_DATATYPE.match(datatype):
            return "string"  # loaded as String (encoded), so ClickHouse accepts it next to a string as before
        if TEMPORAL_DATATYPE.match(datatype):
            return "temporal"
        return "string"  # char, varchar, text, enum, set; json and time are loaded as String

    def convert_using(self, node):
        """CONVERT(expr USING charset), which ClickHouse lacks: the text of ``expr``, as the streaming connector
        renders it (toString). ClickHouse strings are bytes, so the character set needs no conversion; NULL
        stays NULL. toString drops the trailing zeros MySQL prints for a DECIMAL column (1.50 -> 1.5), so a
        decimal column is rendered with its declared scale; a floating-point column (MySQL writes 1e20, toString
        100000000000000000000) and a BIT/BINARY/BLOB column (MySQL reinterprets the bytes, the replica holds them
        encoded) are refused."""
        expression = node.expression()
        datatype = self.direct_column_type(expression)
        if datatype is not None and (FLOAT_DATATYPE.match(datatype) or BINARY_DATATYPE.match(datatype)):
            raise UntranslatableExpression(f"CONVERT ... USING on a {datatype.split('(')[0].strip()} column")
        # a decimal-valued expression (a decimal column, a quotient) is written with MySQL's scale
        (text, changed) = self.text_value(expression)
        return text if changed else f"toString({text})"

    def direct_column_type(self, expression):
        """The MySQL data type of ``expression`` when it is a column of the table (in parentheses or not)."""
        node = expression
        while True:
            if isinstance(node, MySqlParser.PredicateExpressionContext):
                node = node.predicate()
            elif isinstance(node, MySqlParser.ExpressionAtomPredicateContext) and node.LOCAL_ID() is None:
                node = node.expressionAtom()
            elif isinstance(node, MySqlParser.NestedExpressionAtomContext) and len(node.expression()) == 1:
                node = node.expression(0)
            elif isinstance(node, MySqlParser.FullColumnNameExpressionAtomContext):
                return self.column_types.get(node.getText().strip("`").lower())
            elif isinstance(node, MySqlParser.ConstantExpressionAtomContext):
                string = node.constant().stringLiteral()
                if string is not None and string.getChildCount() == 1 and string.getText().startswith("`"):
                    return self.column_types.get(string.getText()[1:-1].replace("``", "`").lower())
                return None
            else:
                return None

    def verbatim(self, node):
        """The node's children rendered, joined by the original text between them."""
        parts = []
        previous_stop = None
        for child in node.getChildren():
            span = self.span(child)
            if span is None:
                continue
            if previous_stop is not None and span[0] > previous_stop + 1:
                parts.append(self.input_stream.getText(previous_stop + 1, span[0] - 1))
            parts.append(self.render(child))
            previous_stop = span[1]
        return "".join(parts)

    @staticmethod
    def span(node):
        if isinstance(node, TerminalNode):
            return (node.getSymbol().start, node.getSymbol().stop)
        if node.start is None or node.stop is None or node.stop.stop < node.start.start:
            return None
        return (node.start.start, node.stop.stop)

    @staticmethod
    def is_bit_not(node):
        return isinstance(node, MySqlParser.UnaryExpressionAtomContext) and node.unaryOperator().getText() == "~"

    # --- bit and arithmetic chains ----------------------------------------------------------------------
    def chain(self, node):
        """(text, kind) of a bit/arithmetic chain: regrouped and translated when it holds a bit operator,
        verbatim otherwise."""
        items = self.flatten(node)
        operators = [items[i].getText().upper() for i in range(1, len(items), 2)]
        if not any(operator in BIT_OPERATORS for operator in operators):
            operand_kinds = [self.operand(items[i])[1] for i in range(0, len(items), 2)]
            return (self.verbatim(node), self.arithmetic_kind(operand_kinds))
        operands = [self.operand(items[i]) for i in range(0, len(items), 2)]
        sequence = [operands[i // 2] if i % 2 == 0 else (items[i], None) for i in range(len(items))]
        return self.regroup(sequence)

    def flatten(self, node):
        """``[operand node, operator, operand node, ...]`` of the contiguous chain under ``node``; operators are the
        operator nodes (kept for their original spelling)."""
        if isinstance(node, MySqlParser.BitExpressionAtomContext):
            operator = node.bitOperator()
        elif isinstance(node, MySqlParser.MathExpressionAtomContext):
            operator = node.mathOperator()
        else:
            return [node]
        return self.flatten(node.left) + [operator] + self.flatten(node.right)

    def regroup(self, sequence, combine=None):
        """Combine ``[operand, (operator node, _), operand, ...]`` left-associatively by MySQL precedence
        (shunting-yard) with ``combine`` (default: the bit/arithmetic rendering)."""
        combine = combine or self.combine

        def precedence(entry):
            return ATOM_OPERATOR_PRECEDENCE[entry[0].getText().upper()]

        operands = [sequence[0]]
        operators = []
        for i in range(1, len(sequence), 2):
            while operators and precedence(operators[-1]) >= precedence(sequence[i]):
                right = operands.pop()
                operands.append(combine(operands.pop(), operators.pop()[0], right))
            operators.append(sequence[i])
            operands.append(sequence[i + 1])
        while operators:
            right = operands.pop()
            operands.append(combine(operands.pop(), operators.pop()[0], right))
        return operands[0]

    def combine(self, left, operator_node, right):
        operator = operator_node.getText().upper()
        if operator not in BIT_OPERATORS:
            return (f"({left[0]} {operator_node.getText()} {right[0]})", self.arithmetic_kind([left[1], right[1]]))
        for kind in (left[1], right[1]):
            if kind == KIND_OTHER:
                raise UntranslatableExpression(f"bit operator {operator} on a non-integer operand")
        (a, b) = (f"toUInt64({left[0]})", f"toUInt64({right[0]})")
        if operator in BIT_FUNCTIONS:
            return (f"{BIT_FUNCTIONS[operator]}({a}, {b})", KIND_INTEGER)
        # ClickHouse shifts by the count modulo 64 on some code paths; MySQL gives 0 for a count of 64 or more.
        # bitAnd(a, 0) keeps a NULL operand NULL, as in MySQL.
        return (f"if({b} >= 64, bitAnd({a}, 0), {SHIFT_FUNCTIONS[operator]}({a}, {b}))", KIND_INTEGER)

    @staticmethod
    def arithmetic_kind(kinds):
        if KIND_OTHER in kinds:
            return KIND_OTHER
        return KIND_INTEGER if all(kind == KIND_INTEGER for kind in kinds) else KIND_UNKNOWN

    # --- operands ---------------------------------------------------------------------------------------
    def operand(self, node):
        """(text, kind) of an operand of a chain."""
        if isinstance(node, (MySqlParser.BitExpressionAtomContext, MySqlParser.MathExpressionAtomContext)):
            return self.chain(node)
        if self.is_bit_not(node):
            (text, kind) = self.operand(node.expressionAtom())
            if kind == KIND_OTHER:
                raise UntranslatableExpression("bit operator ~ on a non-integer operand")
            return (f"bitNot(toUInt64({text}))", KIND_INTEGER)
        return (self.render(node), self.kind(node))

    def kind(self, node):
        if isinstance(node, MySqlParser.FullColumnNameExpressionAtomContext):
            return self.column_kinds.get(node.getText().strip("`").lower(), KIND_UNKNOWN)
        if isinstance(node, MySqlParser.ConstantExpressionAtomContext):
            return self.constant_kind(node.constant())
        if isinstance(node, MySqlParser.UnaryExpressionAtomContext):
            operator = node.unaryOperator().getText().upper()
            return self.operand(node.expressionAtom())[1] if operator in ("-", "+") else KIND_INTEGER
        if isinstance(node, MySqlParser.NestedExpressionAtomContext) and len(node.expression()) == 1:
            return self.expression_kind(node.expression(0))
        return KIND_UNKNOWN

    def constant_kind(self, ctx):
        string = ctx.stringLiteral()
        if string is not None:
            if string.getChildCount() == 1 and string.getText().startswith("`"):
                # The lexer's STRING_LITERAL includes back-quoted strings: in an expression `col` is a column.
                return self.column_kinds.get(string.getText()[1:-1].replace("``", "`").lower(), KIND_UNKNOWN)
            return KIND_OTHER
        if ctx.booleanLiteral() is not None:
            return KIND_INTEGER
        literal = ctx.decimalLiteral()
        if literal is not None:
            return KIND_OTHER if literal.REAL_LITERAL() is not None else KIND_INTEGER
        if ctx.hexadecimalLiteral() is not None or ctx.REAL_LITERAL() is not None or ctx.BIT_STRING() is not None:
            return KIND_OTHER
        return KIND_UNKNOWN  # NULL

    def expression_kind(self, ctx):
        if isinstance(ctx, MySqlParser.PredicateExpressionContext):
            predicate = ctx.predicate()
            if isinstance(predicate, MySqlParser.ExpressionAtomPredicateContext) and predicate.LOCAL_ID() is None:
                return self.operand(predicate.expressionAtom())[1]
            if isinstance(predicate, MySqlParser.ExpressionAtomPredicateContext):
                return KIND_UNKNOWN
            return KIND_INTEGER  # comparisons, IS NULL, IN, BETWEEN, LIKE: 0, 1 or NULL
        return KIND_INTEGER  # NOT, AND/OR/XOR, IS TRUE: 0, 1 or NULL


class CreateTableMySQLParserListener(MySqlParserListener):
    def __init__(self, rmt_delete_support, partition_options, datetime_timezone=None):
        self.buffer = ""
        self.columns = ""
        self.primary_key = ""
        self.columns_map = {}
        self.alter_list = []
        self.rename_list = []
        self.rmt_delete_support = rmt_delete_support
        self.partition_options = partition_options
        self.datatime_timezone = datetime_timezone
        self.has_is_deleted_column = False

    def extract_original_text(self, ctx):
        token_source = ctx.start.getTokenSource()
        input_stream = token_source.inputStream
        start, stop = ctx.start.start, ctx.stop.stop
        return input_stream.getText(start, stop)

    def add_timezone(self, dataTypeText):
        if self.datatime_timezone is not None:
            dataTypeText = dataTypeText[:-1]+",'"+self.datatime_timezone+"')"
        return dataTypeText

    def convertDataType(self, dataType):
        dataTypeText = self.extract_original_text(dataType)
        dataTypeText = re.sub("CHARACTER SET.*", '',
                              dataTypeText, flags=re.IGNORECASE)
        dataTypeText = re.sub("CHARSET.*", '', dataTypeText, flags=re.IGNORECASE)

        if isinstance(dataType, MySqlParser.SimpleDataTypeContext) and dataType.DATE():
            dataTypeText = 'Date32'
        if isinstance(dataType, MySqlParser.DimensionDataTypeContext):
            if dataType.DATETIME() or dataType.TIMESTAMP():
                dataTypeText = 'DateTime64(0)'
                dataTypeText = self.add_timezone(dataTypeText)
                if dataType.lengthOneDimension():
                    dataTypeText = 'DateTime64'+dataType.lengthOneDimension().getText()
                    dataTypeText = self.add_timezone(dataTypeText)
            elif dataType.TIME():
                dataTypeText = "String"

        if BIT1_DATATYPE.match(dataTypeText):
            # Debezium emits BIT(1) as BOOLEAN and the streaming DDL path declares Bool; a String
            # column holding '01'/'00' made every such table DIFFERENT from MySQL (spec 13.04 D-13.04-10).
            return 'Bool'
        if (isinstance(dataType, MySqlParser.SpatialDataTypeContext) and dataType.JSON()) or is_binary_datatype(dataTypeText):
            dataTypeText = 'String'

        return dataTypeText

    def translateColumnDefinition(self, column_name, columnDefinition):
        column_buffer = ''
        dataType = columnDefinition.dataType()
        dataTypeText = self.convertDataType(dataType)
        # data type
        column_buffer += ' ' + dataTypeText

        # data type modifier (NULL / NOT NULL / PRIMARY KEY)
        notNull = False
        notSymbol = True
        nullable = True
        generated = False
        generatedExpression = None
        for child in columnDefinition.getChildren():
            if child.getRuleIndex() == MySqlParser.RULE_columnConstraint:

                if isinstance(child, MySqlParser.NullColumnConstraintContext):
                    nullNotNull = child.nullNotnull()
                    if nullNotNull:
                        text = self.extract_original_text(child)
                        column_buffer += " " + text
                        # SQL keywords are case-insensitive: `null` is the same modifier as `NULL`.
                        if text.upper() == "NULL":
                            nullable = True
                            notNull = True
                            continue

                        if nullNotNull.NOT():
                            notSymbol = True
                        if (nullNotNull.NULL_LITERAL() or nullNotNull.NULL_SPEC_LITERAL()) and notSymbol:
                            notNull = True
                            nullable = False
                        else:
                            notNull = False
                            nullable = True

                if isinstance(child, MySqlParser.PrimaryKeyColumnConstraintContext) and child.PRIMARY():
                    self.primary_key = column_name
                if isinstance(child, MySqlParser.UniqueKeyColumnConstraintContext) and self.unique_key_columns is None:
                    # the first UNIQUE key of the table, column-level form (the streaming DDL path keeps the first one)
                    self.unique_key_columns = [column_name]
                if isinstance(child, MySqlParser.GeneratedColumnConstraintContext):
                    # Translated to `DEFAULT <ClickHouse expression>` in exitColumnCreateTable, once the type of
                    # every column it may reference is known (Spec 13.04 section 3.16).
                    generatedExpression = child.expression()
                    generated = True
        # column without nullable info are default nullable in MySQL, while they are not null in ClickHouse
        if not notNull:
            column_buffer += " NULL"
            nullable = True
        self.generated_expression = generatedExpression
        return (column_buffer, dataType, nullable, generated)

    def exitColumnDeclaration(self, ctx):
        column_text = self.extract_original_text(ctx)

        column_buffer = ""
        column_name = ctx.fullColumnName().getText()

        column_buffer += column_name

        # columns have an identifier and a column definition
        columnDefinition = ctx.columnDefinition()
        dataType = columnDefinition.dataType()
        originalDataTypeText = self.extract_original_text(dataType)

        (columnDefinition_buffer, dataType, nullable, generated) = self.translateColumnDefinition(
            column_name, columnDefinition)

        column_buffer += columnDefinition_buffer

        self.column_types[column_name.strip("`").lower()] = originalDataTypeText
        if generated:
            self.generated_columns.append((len(self.columns), column_name, self.generated_expression))
        self.columns.append(column_buffer)
        dataTypeText = self.convertDataType(dataType)
        if column_name in ['is_deleted','`is_deleted`']:
            self.has_is_deleted_column = True
        if not generated:
            # stored columns in declaration order: the keyless sorting key (sorting_key)
            self.stored_columns.append(column_name)
            if not nullable:
                self.not_null_columns.add(column_name.replace('`', ''))
        # source_column: every entry of columns_map is a MySQL column, never a bookkeeping column the translator
        # appends, so the loader loads it whatever its name (Spec 13.04 section 3.7.1).
        columnMap = {'column_name': column_name, 'datatype': dataTypeText,
                'nullable': nullable, 'mysql_datatype': originalDataTypeText, 'generated': generated, 
                'has_is_deleted_column':self.has_is_deleted_column, 'source_column': True}
        logging.info(str(columnMap))
        self.columns_map.append(columnMap)

    def exitPrimaryKeyTableConstraint(self, ctx):

        text = self.extract_original_text(ctx.indexColumnNames())
        self.primary_key = text

    @staticmethod
    def index_column_names(ctx):
        """Column identifiers of an index column list; sort direction and prefix length dropped.

        Mirrors MySqlDDLParserListenerImpl.indexColumnNames in sink-connector-lightweight."""
        columns = []
        for entry in ctx.getChildren():
            if isinstance(entry, MySqlParser.IndexColumnNameContext):
                if entry.uid() is not None:
                    columns.append(entry.uid().getText())
                elif entry.STRING_LITERAL() is not None:
                    columns.append(entry.STRING_LITERAL().getText())
                else:
                    columns.append(entry.getText())
        return columns

    def exitUniqueKeyTableConstraint(self, ctx):
        # the first UNIQUE key of the table, table-level form
        if self.unique_key_columns is None and ctx.indexColumnNames() is not None:
            names = self.index_column_names(ctx.indexColumnNames())
            if names:
                self.unique_key_columns = names

    def enterColumnCreateTable(self, ctx):
        self.buffer = ""
        self.columns = []
        self.columns_map = []
        self.primary_key = 'tuple()'
        self.partition_keys = None
        self.unique_key_columns = None
        self.stored_columns = []
        self.not_null_columns = set()
        self.column_types = {}
        self.generated_columns = []

    def translate_generated_columns(self, tableName):
        """Append `DEFAULT <expression>` to every generated column.

        DEFAULT, not MATERIALIZED, as the streaming DDL path does (MySqlDDLParserListenerImpl, sink-connector-
        lightweight): rows loaded from a dump, which carries no generated values, get the value computed from
        their columns, and streamed rows, which carry MySQL's value, keep it; a MATERIALIZED column rejects an
        INSERT that names it. VIRTUAL and STORED are treated alike. The expression is passed through as written
        except for the constructs ClickHouse rejects or renders differently (GeneratedExpressionTranslator); an
        expression ClickHouse would evaluate differently and that never loaded refuses the table (Spec 13.04
        D-13.04-33). A string-typed column stores the text of its value, so a decimal value is written with
        MySQL's scale."""
        for (position, column_name, expression) in self.generated_columns:
            translator = GeneratedExpressionTranslator(self.column_types, expression.start.getTokenSource().inputStream,
                                                       tableName, column_name)
            datatype = self.column_types.get(column_name.strip("`").lower(), "")
            decimal = DECIMAL_DATATYPE.match(datatype)
            try:
                translated = translator.translate(expression, text_context=bool(STRING_DATATYPE.match(datatype)),
                                                  decimal_scale=int(decimal.group(4) or 0) if decimal else None)
            except UntranslatableExpression as ex:
                raise UnsafeTableDefinitionError(
                    f"Table {tableName}: generated column {column_name} has an expression ClickHouse would evaluate "
                    f"differently from MySQL ({ex}): {self.extract_original_text(expression)}. Refusing to create "
                    f"the table rather than emit DDL that computes different values.") from None
            self.columns[position] += " DEFAULT " + translated

    def exitPartitionClause(self, ctx):
        if ctx.partitionTypeDef():
            partitionTypeDef = ctx.partitionTypeDef()
            if partitionTypeDef.RANGE_SYMBOL() and partitionTypeDef.COLUMNS_SYMBOL():
                text = self.extract_original_text(
                    partitionTypeDef.identifierList())
                self.partition_keys = text

    def sorting_key(self, tableName):
        """ORDER BY clause and extra table SETTINGS, derived as the streaming connector derives them.

        Mirrors MySqlDDLParserListenerImpl.enterColumnCreateTable (sink-connector-lightweight): the PRIMARY KEY;
        else the first UNIQUE key when every column of it is NOT NULL; else every stored (non-generated) column in
        declaration order, plus allow_nullable_key=1 when one of them is nullable. ORDER BY tuple() is never
        emitted: ReplacingMergeTree would collapse the whole table into one row (Spec 13.04 section 3.12)."""
        if self.primary_key != 'tuple()':
            return (self.primary_key, '')
        if self.unique_key_columns:
            bare = [re.sub(r"\(\d+\)$", '', c.replace('`', '').strip()) for c in self.unique_key_columns]
            if all(c in self.not_null_columns for c in bare):
                logging.info(f"Table {tableName} has no PRIMARY KEY; using its NOT NULL UNIQUE key as the "
                             f"sorting key: {self.unique_key_columns}")
                return ("(" + ",".join(self.unique_key_columns) + ")", '')
            logging.warning(f"Table {tableName} has no PRIMARY KEY and its UNIQUE key {self.unique_key_columns} "
                            f"spans nullable columns, so it is not a row identity; falling back to all columns")
        if not self.stored_columns:
            raise UnsafeTableDefinitionError(
                f"Cannot derive a sorting key for {tableName}: no PRIMARY KEY, no NOT NULL UNIQUE key and no stored "
                f"(non-generated) column. Refusing to create ReplacingMergeTree ... ORDER BY tuple(), which would "
                f"collapse every row into one.")
        settings = ''
        if any(c.replace('`', '') not in self.not_null_columns for c in self.stored_columns):
            settings = ' SETTINGS allow_nullable_key=1'
        logging.warning(f"Table {tableName} has no PRIMARY KEY and no NOT NULL UNIQUE key; using every stored column "
                        f"as the ReplacingMergeTree sorting key so distinct rows stay distinct: {self.stored_columns}. "
                        f"Rows identical in every column still collapse; add a PRIMARY KEY at the source.")
        return ("(" + ",".join(self.stored_columns) + ")", settings)

    def exitColumnCreateTable(self, ctx):
        tableName = self.extract_original_text(ctx.tableName())
        self.buffer = f"CREATE TABLE {tableName} ("
        self.translate_generated_columns(tableName)
        is_deleted_column = 'is_deleted'
        if self.has_is_deleted_column:
            is_deleted_column = '_is_deleted'
        # is_deleted and _sign are redundant, so exclusive in the schema
        bookkeeping = ['_version', is_deleted_column if self.rmt_delete_support else '_sign']
        # A source column named like an appended bookkeeping column would duplicate it in the DDL, and in a
        # --data_only load its MySQL values would land in the bookkeeping column. MySQL owns its columns, so
        # refuse loudly instead of creating a table that cannot hold them (Spec 13.04 section 3.11).
        source_names = [m['column_name'].replace('`', '') for m in self.columns_map]
        collisions = [name for name in bookkeeping if name in source_names]
        if collisions:
            hint = ""
            if not self.rmt_delete_support and '_sign' in collisions:
                hint = " or load with --rmt_delete_support (is_deleted replaces _sign)"
            raise UnsafeTableDefinitionError(
                f"Table {tableName}: source column(s) {collisions} collide with the bookkeeping column(s) "
                f"{bookkeeping} the loader appends. The MySQL column cannot be stored faithfully; rename it at the "
                f"source{hint}.")
        (order_by, settings) = self.sorting_key(tableName)
        self.columns.append("`_version` UInt64 DEFAULT 0")
        if self.rmt_delete_support: 
            self.columns.append(f"`{is_deleted_column}` UInt8 DEFAULT 0")
        else:
            self.columns.append("`_sign` Int8 DEFAULT 1")

        for column in self.columns:
            self.buffer += column
            if column != self.columns[-1]:
                self.buffer += ','
            self.buffer += '\n'

        partition_by = self.partition_options
        if self.partition_keys:
            partition_by = f"partition by {self.partition_keys}"
        rmt_params = "_version"
        if self.rmt_delete_support:
            rmt_params += f",{is_deleted_column}"

        self.buffer += f") engine=ReplacingMergeTree({rmt_params}) {partition_by} order by " + \
            order_by + settings
        logging.info(self.buffer)

    def get_clickhouse_sql(self):
        return (self.buffer, self.columns_map)

    def exitAlterList(self, ctx):
        for child in ctx.getChildren():
            if isinstance(child, MySqlParser.AlterListItemContext):
                alter = self.extract_original_text(child)

                if child.ADD_SYMBOL() and child.fieldDefinition():
                    fieldDefinition = child.fieldDefinition()
                    if child.identifier():
                        identifier = self.extract_original_text(
                            child.identifier())
                        place = self.extract_original_text(
                            child.place()) if child.place() else ''

                        (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(
                            identifier, fieldDefinition)
                        alter = f"add column {identifier} {fieldDefinition_buffer} {place}"
                        self.alter_list.append(alter)

                if child.MODIFY_SYMBOL() and child.fieldDefinition():
                    fieldDefinition = child.fieldDefinition()
                    if child.columnInternalRef():
                        identifier = self.extract_original_text(
                            child.columnInternalRef().identifier())
                        place = self.extract_original_text(
                            child.place()) if child.place() else ''
                        (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(
                            identifier, fieldDefinition)
                        alter = f"modify column {identifier} {fieldDefinition_buffer} {place}"
                        self.alter_list.append(alter)

                if child.CHANGE_SYMBOL() and child.fieldDefinition():
                    fieldDefinition = child.fieldDefinition()
                    if child.columnInternalRef():
                        identifier = self.extract_original_text(
                            child.columnInternalRef().identifier())
                        place = self.extract_original_text(
                            child.place()) if child.place() else ''
                        (fieldDefinition_buffer, dataType) = self.translateFieldDefinition(
                            identifier, fieldDefinition)
                        alter = f"modify column {identifier} {fieldDefinition_buffer} {place}"
                        self.alter_list.append(alter)
                        new_identifier = self.extract_original_text(
                            child.identifier())
                        rename_column = f"rename column {identifier} to {new_identifier}"
                        self.alter_list.append(rename_column)

                if child.DROP_SYMBOL() and child.COLUMN_SYMBOL():
                    self.alter_list.append(alter)

                if child.RENAME_SYMBOL() and child.COLUMN_SYMBOL():
                    self.alter_list.append(alter)

                if child.RENAME_SYMBOL() and child.tableName():
                    to_table = self.extract_original_text(
                        child.tableName().qualifiedIdentifier())
                    rename = f" to {to_table}"
                    self.rename_list.append(rename)

    def exitAlterTable(self, ctx):
        tableName = self.extract_original_text(ctx.tableName())

        for child in ctx.getChildren():
            if isinstance(child, MySqlParser.AlterByRenameContext):
                if child.RENAME():
                    if child.uid():
                        rename = self.extract_original_text(child.uid())
                    if child.fullId():
                        rename = self.extract_original_text(child.fullId())

                    self.buffer += f" rename table {tableName} to {rename}"

        # if len(self.alter_list):
        #  self.buffer = f"ALTER TABLE {tableName}"
        #  for alter in self.alter_list:
        #    self.buffer += ' ' + alter
        #    if self.alter_list[-1] != alter:
        #      self.buffer += ', '
        #  self.buffer += ';'

             # for rename in self.rename_list:
             # self.buffer += f" rename table {tableName} {rename}"
             # self.buffer += ';'

    def exitRenameTable(self, ctx):
        # same syntax as CH
        self.buffer = self.extract_original_text(ctx)

    def exitTruncateTable(self, ctx):
        # same syntax as CH
        self.buffer = self.extract_original_text(ctx)

    def exitDropTable(self, ctx):
        # same syntax as CH
        self.buffer = self.extract_original_text(ctx)
