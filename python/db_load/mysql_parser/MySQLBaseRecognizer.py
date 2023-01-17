from antlr4 import *
serverVersion = 80000
class MySQLBaseRecognizer(Parser):
    serverVersion = 80000
    def __init__(self, input, output):
       super().__init__(input)
