%{
  #include <bits/stdc++.h>
  #include "P1.tab.h"
  void yyerror(const char *);
  bool neg=false;
  
  extern int line_counter;
  using namespace std;
%}

%%
";"             {return ';';}
"main"          {return MAIN;}
"import"        {return IMPORT;}
"java.util.function."   {return JAVALIB;} 
"class"         {return CLASS;}
"public"        {return PUBLIC;}
"Function"      {return FUNCTION;}
"#define"       {return DEFINE;}

"static"        {return STATIC;}
"void"          {return VOID;} 
"System.out.println"  {return PRINT;}
"extends"       {return EXTENDS;}
"length"        {return LENGTH;}

"if"            { return IF;}
"else"          {return ELSE;}
"while"         {return WHILE; }
"return"        {return RETURN;}

"int"           {return INT;}
"String"        {return STRING;}
"boolean"       {return BOOLEAN;}
"true"          {return B_TRUE;}
"false"         {return B_FALSE;}

"this"          {return THIS;}
"new"           {return NEW;}

"&&"            {return AND;}
"||"            {return OR;}
"!="            {return NEQ;}
"<="            {return LEQ;}
"+"             {return PLUS;}
"-"             {return MINUS;}
"*"             {return MUL;}
"/"             {return DIV;}
"="             {return ASSIGN;}
","             {return COMMA;}
"."             {return DOT;}
"<"             {return '<';}
">"             {return '>';}
"("             {return '(';}
")"             {return ')';}
"["             {return '[';}
"]"             {return ']';}
"{"             {return'{';}
"}"             {return'}';}
"->"            {return RAY;}
"!"             {return '!';}
"//"[^\n]*          {/*ignore comment till eofl*/}
"\n"            { line_counter++; }





[0]|[1-9][0-9]*  {
            yylval.val = strdup(yytext);
            // printf("NUMBER\n");
            return NUMBER;
      }

[ \n\t\r]           {  }
            
[a-zA-Z_][a-zA-Z0-9_]*    { //printf("IDENTIFIER\n"); 
  yylval.val = strdup(yytext);
    return IDENTIFIER; }
.             {yyerror("ERROR");}

%%

int yywrap(void){
  
  return 1;
}

/* int main(){
  yylex();
  return 0;
} */