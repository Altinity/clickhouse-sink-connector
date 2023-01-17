from antlr4 import *
import sys


class MySQLBaseLexer(Lexer):
     def __init__(self, input, output=sys.stdout):
       self.serverVersion=90000
       self.NOT_SYMBOL=1
       self.NoBackslashEscapes=1
       self.HighNotPrecedence=1
       self.inVersionComment=False
       super().__init__(input, output=sys.stdout)

       def isSqlModeActive(self, attr):
              return False

       def determineNumericType(self, text):
              None

       def checkVersionMySQL(self, text):
              if text is not None:
                     if (len(text) < 8): 
                            # Minimum is: /*!12345
                            return False
              # Skip version comment introducer.
              version = int(text[3:])
              print(f" {version} {serverVersion}")
              if (version <= serverVersion):
                     inVersionComment = False
                     return True
              inVersionComment = False
              return False


       def setType(self, type):
              None

       def isSqlModeActive(self, attr):
              return False 

       def reset(self):
              print("reset")
              self.inVersionComment = False
              super().reset()
