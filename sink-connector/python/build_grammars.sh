wget https://download.java.net/java/GA/jdk19.0.2/fdb695a9d9064ad6b064dc6df578380c/7/GPL/openjdk-19.0.2_linux-x64_bin.tar.gz
wget https://www.antlr.org/download/antlr-4.11.1-complete.jar
tar zxvf openjdk-19.0.2_linux-x64_bin.tar.gz
cp antlr_grammars/mysql/*.g4 .
jdk-19.0.2/bin/java -Xmx500M -cp antlr-4.11.1-complete.jar org.antlr.v4.Tool -Dlanguage=Python3  -no-visitor  MySqlParser.g4  MySqlLexer.g4 -o db_load/mysql_parser

# ---------------------------------------------------------------------------
# PostgreSQL grammar generation
# ---------------------------------------------------------------------------
# The PostgreSQL grammar requires the Lexer to be listed before the Parser so
# that ANTLR can resolve the tokenVocab reference in PostgreSQLParser.g4.
# Base-class Python stubs (PostgreSQLLexerBase.py / PostgreSQLParserBase.py)
# are copied into the output directory so the generated code can import them.
# ---------------------------------------------------------------------------
mkdir -p db_load/postgres_parser

# Copy base-class stubs into the output directory
cp antlr_grammars/postgres/PostgreSQLLexerBase.py  db_load/postgres_parser/
cp antlr_grammars/postgres/PostgreSQLParserBase.py db_load/postgres_parser/

# Run ANTLR on both grammar files from the grammar source directory so that
# relative imports inside the .g4 files resolve correctly.
(
  cd antlr_grammars/postgres
  ../../jdk-19.0.2/bin/java -Xmx500M \
    -cp ../../antlr-4.11.1-complete.jar org.antlr.v4.Tool \
    -Dlanguage=Python3 -no-visitor -listener \
    PostgreSQLLexer.g4 PostgreSQLParser.g4 \
    -o ../../db_load/postgres_parser
)
