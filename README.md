# miniJavaCompiler

A multi-stage **MiniJava compiler project** developed as part of **CS3300 – Compiler Design**. The project implements the major stages of a compiler pipeline, from MacroJava parsing and semantic analysis to intermediate code generation, register allocation, and MIPS code generation.

## Project Structure

```text
miniJavaCompiler/
├── interpreter/
├── P1/
├── P1/
├── P2/
├── P3/
├── P4/
├── P5/
└── P6/
```

## Compiler Pipeline

```text
MacroJava
    │
    ▼
P1 : Macro Expansion & Parsing
    │
    ▼
MiniJava
    │
    ▼
P2 : Type Checking
    │
    ▼
Type-Checked MiniJava
    │
    ▼
P3 : MiniIR Generation
    │
    ▼
MiniIR
    │
    ▼
P4 — MicroIR Generation
    │
    ▼
MicroIR
    │
    ▼
P5 : Register Allocation
    │
    ▼
MiniRA
    │
    ▼
P6 : MIPS Code Generation
    │
    ▼
MIPS Assembly
```

---

## P1 MacroJava to MiniJava Translation

Translates MacroJava programs into MiniJava using **Flex and Bison**.

### Features

* MacroJava parsing
* Macro expansion
* MiniJava code generation
* Syntax error handling
* Standard input/output support

### Build and Run

```bash
bison -d P1.y
flex P1.l
gcc P1.tab.c lex.yy.c -lfl -o P1
./P1 < X.java > Y.java
```

If the input MacroJava program cannot be parsed:

```text
// Failed to parse macrojava code.
```

---

## P2 MiniJava Type Checker

Performs semantic analysis on MiniJava programs using **JTB, JavaCC, and Java**.

### Features

* Type checking
* Variable scope checking
* Field lookup
* Method lookup
* Detection of undeclared symbols

### Output

The program produces one of:

```text
Program type checked successfully
Type error
Symbol not found
```

### Run

```bash
java P2 < P.java
```

---

## P3 MiniJava to MiniIR

Translates type-checked MiniJava programs into semantically equivalent **MiniIR** programs.

### Run

```bash
java P3 < P.java > P.miniIR
```

### Verification

The generated MiniIR can be executed using the provided interpreter:

```bash
java -jar pgi.jar < P.miniIR
```

The output can be compared with the output of the original MiniJava program to verify semantic equivalence.

---

## P4 MiniIR to MicroIR

Translates **MiniIR** programs into semantically equivalent **MicroIR** programs.

### Run

```bash
java P4 < P.miniIR > P.microIR
```

### Verification

```bash
java -jar pgi.jar < P.microIR
```

The output can be compared with the corresponding MiniIR execution.

---

## P5 — Register Allocation

Translates **MicroIR** programs into **MiniRA** by performing register allocation.

### Run

```bash
java P5 < P.microIR > P.RA
```

### Verification

The generated MiniRA program can be executed using the provided interpreter:

```bash
java -jar kgi.jar < P.RA
```

The output can be compared with the corresponding MicroIR execution to verify semantic equivalence.

---

## P6 — MIPS Code Generation

Translates **MiniRA** programs into **MIPS assembly**.

### Run

```bash
java P6 < P.miniRA > P.s
```

The generated assembly can be executed using a MIPS simulator such as **SPIM** to verify semantic equivalence.

---

## Technologies Used

* C
* Java
* Flex
* Bison
* JTB
* JavaCC
* MiniJava
* MiniIR
* MicroIR
* MiniRA
* MIPS Assembly
* SPIM

## Assignment Overview

| Directory      | Stage                        | Input       | Output                 |
| -------------- | ---------------------------- | ----------- | ---------------------- |
| `P1/`          | Macro expansion & parsing    | MacroJava   | MiniJava               |
| `P2/`          | Type checking                | MiniJava    | Type-check result      |
| `P3/`          | Intermediate code generation | MiniJava    | MiniIR                 |
| `P4/`          | IR simplification            | MiniIR      | MicroIR                |
| `P5/`          | Register allocation          | MicroIR     | MiniRA                 |
| `P6/`          | Code generation              | MiniRA      | MIPS Assembly          |
| `interpreter/` | Supporting tools             | IR programs | Execution/verification |

## Course

**CS3300 — Compiler Design**
**Indian Institute of Technology Madras**

This project implements a complete multi-stage compiler pipeline, with each stage transforming the program into an intermediate representation suitable for the next phase of compilation.
