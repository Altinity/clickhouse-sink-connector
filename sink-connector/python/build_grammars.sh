wget https://download.java.net/java/GA/jdk19.0.2/fdb695a9d9064ad6b064dc6df578380c/7/GPL/openjdk-19.0.2_linux-x64_bin.tar.gz
wget https://www.antlr.org/download/antlr-4.11.1-complete.jar
tar zxvf openjdk-19.0.2_linux-x64_bin.tar.gz
cp antlr_grammars/mysql/*.g4 .
jdk-19.0.2/bin/java -Xmx500M -cp antlr-4.11.1-complete.jar org.antlr.v4.Tool -Dlanguage=Python3  -no-visitor  MySqlParser.g4  MySqlLexer.g4 -o db_load/mysql_parser
