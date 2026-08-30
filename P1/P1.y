
%{ 
    #include <bits/stdc++.h>
    #include <stdio.h>
    #include <stdlib.h>
    #include <string.h>
    using namespace std;
    int line_counter = 1;
    int indent_count = 0;
    void yyerror(const char *);
    int yylex(void);
    extern char* yytext;
    map<string,const char*>mp;
    string indent(){
        string res = "";
        for(int i=0;i<indent_count;i++){
            res += "    ";  
        }
        return res;
    }
    struct Macro{ 
        vector<string>parameters; 
        string body; 
        bool is_from_Expression;
    }; 
    unordered_map<string,Macro>macros;
    vector<string> list_parameters(const string &s){
        vector<string>result;
        string liststr = "";
        for(char c : s){
            if(c != ','){
                liststr += c;
            }else{
                // remove spaces
                int l = 0;
                while(l<(int)liststr.size() && isspace(liststr[l])) l++;
                int r = (int)liststr.size()-1;
                while(r >= l && isspace(liststr[r]))r--;
                if(l <= r){
                    result.push_back(liststr.substr(l,r-l+1));
                }
                liststr = "";
            }
        }
        // final parameter
        int l = 0;
        while(l<(int)liststr.size() && isspace(liststr[l])) l++;
        int r = (int)liststr.size() - 1;
        while(r >= l && isspace(liststr[r])) r--;
        if(l <= r){
            result.push_back(liststr.substr(l,r-l+1));
        }
        return result;
    }
    vector<string> list_arguments(const string &s){
        vector<string> result;
        string arglist = "";
        int para_count = 0;
        for(char c : s){
            if(c == ',' && para_count == 0){
                // remove spaces
                int l = 0;
                while(l < (int)arglist.size() && isspace(arglist[l])) l++;
                int r = (int)arglist.size() - 1;
                while(r >= l && isspace(arglist[r])) r--;
                if(l <= r){
                    result.push_back(arglist.substr(l, r - l + 1));
                }
                arglist = "";
            }else{
                if (c == '(') para_count++;
                if (c == ')') para_count--;
                arglist += c;
            }
        }
        int l = 0;
        while(l < (int)arglist.size() && isspace(arglist[l])) l++;
        int r = (int)arglist.size()-1;
        while(r >= l && isspace(arglist[r])) r--;
        if(l <= r){
            result.push_back(arglist.substr(l,r-l+1));
        }
        return result;
    }
    
    string replace_parameters(const string &body,const vector<string>&parameter_list,const vector<string>&arg_list){
        unordered_map<string,string>para_arg;
        for(int i=0;i<parameter_list.size();i++){
            para_arg[parameter_list[i]] = arg_list[i];
        }
        string res = "";
        int i = 0;
        int n = body.size();
        while (i < n) {
            if(isalpha(body[i]) || body[i]=='_'){
                int j=i+1;
                while(j<n && (isalnum(body[j]) || body[j]=='_')){
                    j++;
                }
                string window = body.substr(i,j-i);
                bool replaced = false;
                for(int k=0;k<parameter_list.size();k++){
                    if(window == parameter_list[k]){
                        res += "(" + arg_list[k] + ")";
                        replaced = true;
                        break;
                    }
                }
                if(!replaced){
                    res += window;
                }
                i=j;
            }else{
                res.push_back(body[i]);
                i++;
            }
        }
        return res;
    }

    bool compare1(const string &a, const string &b){
        return a.size() > b.size();
    }

    string substitute_macro(string code){
    bool changed = true;
    while(changed){
        changed = false;
        string new_code;
        int n = code.size();
        int i = 0;
        vector<string>names;
        names.reserve(macros.size());
        for(auto const &p : macros){
            names.push_back(p.first);
        }
        sort(names.begin(),names.end(),compare1);
        while(i < n){
            bool matched = false;
            for(const string &id : names){
                int len = id.size();
                if(i+len > n) continue;
                if(code.compare(i,len,id) != 0) continue;
                if(i>0){
                    char pc = code[i-1];
                    if(isalnum(pc) || pc == '_' || pc == '.') continue;
                }
                int j = i+len;
                while(j < n && isspace((char)code[j])) j++;
                if(j>=n || code[j]!='(') continue;
                int k = j+1;
                int paren = 1;
                while(k<n && paren>0){
                    if(code[k] == '(')paren++;
                    else if(code[k] == ')')paren--;
                    k++;
                }
                if(paren != 0){
                    continue;
                }
                int args_start = j+1;
                int args_len = k-j-2; 
                string args_str = (args_len>0) ? code.substr(args_start, args_len) : string("");
                vector<string> args = list_arguments(args_str);
                Macro m = macros[id];
                string body = m.body;
                if (args.size() == m.parameters.size()) {
                    body = replace_parameters(body,m.parameters,args);
                }
                new_code += body;
                i = k; 
                matched = true;
                changed = true;
                break; 
            }
            if(!matched){
                new_code.push_back(code[i]);
                i++;
            }
        } 
        code.swap(new_code);
    }
    return code;
}

%}

%union {
    const char* val;
    int valid;
}

%token <val> IDENTIFIER NUMBER 
%token MAIN IMPORT JAVALIB CLASS PUBLIC FUNCTION STATIC VOID PRINT EXTENDS LENGTH
%token INT STRING BOOLEAN B_TRUE B_FALSE NEQ LEQ OR AND ASSIGN DOT THIS NEW DEFINE
%token IF ELSE WHILE RETURN PLUS MINUS MUL DIV RAY COMMA

%type <val> Goal MainClass TypeDeclaration MethodDeclaration Type Statement Expression PrimaryExpression 
%type <val> MacroDefinition MacroDefStatement MacroDefExpression ImportFunction_optional MacroDefination_all 
%type <val> MethodDeclares Statements_all Expression_all Expression_optional OptionalParameters ParameterList
%type <val> LambdaParameter VarDeclaration VarDeclList TypeDeclaration_all

%left OR
%left AND
%left NEQ LEQ
%left PLUS MINUS
%left MUL DIV
%right '!'


%%
Goal:
    ImportFunction_optional MacroDefination_all MainClass TypeDeclaration_all 
        { $$ = strdup((string($1) + string($2) + string($3) + string($4)).c_str()); cout << $$ << "\n"; }
    ;

ImportFunction_optional:
    IMPORT JAVALIB FUNCTION ';'
        { $$ = strdup("import java.util.function.Function;\n"); }
    | { $$ = strdup(""); }
    ;

MainClass:
    CLASS IDENTIFIER '{' PUBLIC STATIC VOID MAIN '(' STRING '[' ']' IDENTIFIER ')' '{' Statements_all '}' '}'
        {
            string start = indent() + "class " + string($2) + " {\n\t" + "public static void main(String[] " + string($12) + ") {\n";
            indent_count++;
            string middle = substitute_macro(string($15));
            indent_count--;
            string end = indent() + "\t}\n" + indent() + "}\n";
            $$ = strdup((start + middle + end).c_str());
        }
    ;

TypeDeclaration_all:
    TypeDeclaration_all TypeDeclaration 
        { $$ = strdup((string($1) + string($2)).c_str()); }
    | { $$ = strdup(""); }
    ;

TypeDeclaration:
    CLASS IDENTIFIER '{' VarDeclList MethodDeclares '}'
        { 
            string start = indent() + "class " + string($2) + " {\n";
            indent_count++;
            string middle = string($4) + string($5); 
            indent_count--;
            string end = indent() + "}\n";
            $$ = strdup((start + middle + end).c_str());
        }
    | CLASS IDENTIFIER EXTENDS IDENTIFIER '{' VarDeclList MethodDeclares '}'
        { 
            string start = indent() + "class " + string($2) + " extends " + string($4) + " {\n";
            indent_count++;
            string middle = string($6) + string($7); 
            indent_count--;
            string end = indent() + "}\n";
            $$ = strdup((start + middle + end).c_str());

        }
    ;


MethodDeclares:
    MethodDeclares MethodDeclaration 
        { $$ = strdup((string($1) + string($2)).c_str()); }
    | { $$ = strdup(""); }
    ;

MethodDeclaration:
    PUBLIC Type IDENTIFIER '(' OptionalParameters ')' '{' VarDeclList Statements_all RETURN Expression ';' '}'
        {   
            string start = indent() + "public " + string($2) + $3 + "(" + string($5) + "){\n";
            indent_count++;
            string middle = substitute_macro(string($8)) + substitute_macro(string($9)) + indent() + "return " + substitute_macro(string($11)) + ";\n";
            indent_count--;
            string end = indent() + "}\n";
            $$ = strdup(( start + middle + end).c_str());
        }
    ;

Type:
    INT '[' ']'     { $$ = strdup("int[] "); }
    | BOOLEAN       { $$ = strdup("boolean "); }
    | INT           { $$ = strdup("int "); }
    | IDENTIFIER    { $$ = strdup((string($1)+" ").c_str()); }
    | FUNCTION '<' IDENTIFIER COMMA IDENTIFIER '>'
        { $$ = strdup((string("Function<") + string($3) + "," + $5 + "> ").c_str()); }
    ;

Statements_all:
    Statement Statements_all
        { $$ = strdup((string($1) + string($2)).c_str()); }
    | { $$ = strdup(""); }
    ;

VarDeclList:
    VarDeclList VarDeclaration   { $$ = strdup((string($1) + $2).c_str()); }
    | { $$ = strdup(""); }

VarDeclaration:
    Type IDENTIFIER ';' 
        { 
            $$ = strdup((string(indent()) + string($1) + string($2) + ";\n").c_str()); 
        }
    ;


Statement:
    '{' Statements_all '}'
        {   string start = indent() + "{\n";
            indent_count++;
            string middle = string($2);
            indent_count--;
            string end = indent() + "}\n";
            $$ = strdup((start + middle + end).c_str());
        }
    | PRINT '(' Expression ')' ';'
        {   
            $$ = strdup((indent() + "System.out.println(" + string($3) + ");\n").c_str());
            
        }
    | IDENTIFIER ASSIGN Expression ';'
        {
            
            $$ = strdup((indent() + string($1) + " = " + string($3) + ";\n").c_str());
            
        }
    | IDENTIFIER '[' Expression ']' ASSIGN Expression ';'
        {
            
            $$ = strdup((indent() + string($1) + "[" + string($3) + "] = " + string($6) + ";\n").c_str());
            
        }
    | IF '(' Expression ')' Statement
        {
            $$ = strdup((string(indent()) + string("if(") + string($3) + ")\n" + $5).c_str());
        }
    | IF '(' Expression ')' Statement ELSE Statement
        {
            $$ = strdup((string(indent()) + "if(" + string($3) + ")\n" + $5 + indent() + "else\n" + $7).c_str());
        }
    | WHILE '(' Expression ')' Statement 
        {
            $$ = strdup((indent() + string("while(" + string($3) + ")" + string($5))).c_str());
        }
    | IDENTIFIER '(' Expression_optional ')' ';'
        {
            string id = string($1);
            if(macros.count(id)) {
                Macro m = macros[id];
                if(m.is_from_Expression){
                    yyerror("ERROR");
                }
                string final = substitute_macro(id + "(" + string($3) + ")");
                $$ = strdup((indent() + final).c_str());
            } else {
                $$ = strdup((id + "(" + string($3) + ");\n").c_str());
            }
        }
    ;

Expression_optional:
    Expression_all    { $$ = strdup((string($1)).c_str()); }
    | { $$ = strdup(""); }

Expression_all:
    Expression_all COMMA Expression
        { $$ = strdup((string($1) + string(", ") + string($3)).c_str()); }
    | Expression { $$ = strdup((string($1)).c_str()); }
    ;

Expression:
    PrimaryExpression                               { $$ = strdup($1); }
    | PrimaryExpression AND PrimaryExpression       { $$ = strdup((string($1) + " && " + $3).c_str()); }
    | PrimaryExpression OR PrimaryExpression        { $$ = strdup((string($1) + " || " + $3).c_str()); }
    | PrimaryExpression NEQ PrimaryExpression       { $$ = strdup((string($1) + " != " + $3).c_str()); }
    | PrimaryExpression LEQ PrimaryExpression       { $$ = strdup((string($1) + " <= " + $3).c_str()); }
    | PrimaryExpression PLUS PrimaryExpression      { $$ = strdup((string($1) + " + " + $3).c_str()); }
    | PrimaryExpression MINUS PrimaryExpression     { $$ = strdup((string($1) + " - " + $3).c_str()); }
    | PrimaryExpression MUL PrimaryExpression       { $$ = strdup((string($1) + " * " + $3).c_str()); }
    | PrimaryExpression DIV PrimaryExpression       { $$ = strdup((string($1) + " / " + $3).c_str()); }
    | PrimaryExpression '[' PrimaryExpression ']'   { $$ = strdup((string($1) + "[" + $3 + "]").c_str()); }
    | PrimaryExpression DOT LENGTH                  { $$ = strdup((string($1) + ".length").c_str()); }
    | PrimaryExpression DOT IDENTIFIER '(' Expression_optional ')'
        { $$ = strdup((string($1) + "." + string($3) + "(" + string($5) + ")").c_str()); }
    | IDENTIFIER '(' Expression_optional ')'
        {
            string id = string($1);
            if(macros.count(id)) {
                Macro m = macros[id];
                if(!m.is_from_Expression){
                    yyerror("ERROR");
                }
                string final = substitute_macro(id + "(" + string($3) + ")");
                $$ = strdup(final.c_str());
            } else {
                $$ = strdup((id + "(" + string($3) + ")").c_str());
            }
        }
    | LambdaParameter RAY Expression
        { $$ = strdup((string("(") + string($1) + " -> " + string($3) + ")").c_str()); }
    ;

PrimaryExpression:
    NUMBER              { $$ = strdup($1); }
    | B_TRUE            { $$ = strdup("true"); }
    | B_FALSE           { $$ = strdup("false"); }
    | NEW IDENTIFIER '(' ')'
        { $$ = strdup((string("new ") + string($2) + "()").c_str()); }
    | THIS              { $$ = strdup("this"); }
    | IDENTIFIER        { $$ = strdup($1); }
    | NEW INT '[' Expression ']'
        { $$ = strdup((string("new int[") + string($4) + "]").c_str()); }
    | '!' Expression
        { $$ = strdup((string("!(") + string($2) + string(")")).c_str()); }
    | '(' Expression ')'
        { $$ = strdup((string("(") + string($2) + string(")")).c_str()); }
    ;

MacroDefination_all:
    MacroDefination_all MacroDefinition   { $$ = strdup((string($1) + string($2)).c_str()); }
    | { $$ = strdup(""); }
    ;

MacroDefinition:
    MacroDefExpression   { $$ = strdup($1); }
    | MacroDefStatement  { $$ = strdup($1); }
    ;

MacroDefStatement:
    DEFINE IDENTIFIER '(' Expression_optional ')' '{' {indent_count++;} Statements_all {indent_count--;} '}'
        { 
            Macro m;
            m.parameters = list_parameters(string($4));
            m.body = string($8);
            m.is_from_Expression = false;
            macros[string($2)] = m;
            $$ = strdup(""); 
        }
    ;

MacroDefExpression:
    DEFINE IDENTIFIER '(' Expression_optional ')' '(' Expression ')'
        { 
            Macro m;
            m.parameters = list_parameters(string($4));
            m.body = string($7);
            m.is_from_Expression = true;
            macros[string($2)] = m;
            $$ = strdup(""); 
        }
    ;

OptionalParameters:
    /* empty */ { $$ = strdup(""); }
    | ParameterList { $$ = strdup((string($1)).c_str()); }
    ;

ParameterList:
    Type IDENTIFIER { $$ = strdup((string($1) + $2).c_str()); }
    | ParameterList COMMA Type IDENTIFIER  { $$ = strdup((string($1) + ", " + $3 + $4).c_str()); }
    ;

LambdaParameter:
    '(' IDENTIFIER ')' 
        {
            $$ = strdup((string("(") + string($2) + ")").c_str());
        }

%%

void yyerror(const char *s) {
    fprintf(stderr, "Parse error: %s at line %d (last token = %s)\n", s, line_counter, yytext);
    printf("// Failed to parse macrojava code.\n");
    exit(1);
}


int main(void) {
    yyparse();
    return 0;
}
