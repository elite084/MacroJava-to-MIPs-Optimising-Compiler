package visitor;

import java.util.*;
import syntaxtree.*;

public class CodeGenVisitor extends GJNoArguDepthFirst<Void> {
    private Map<String, ProcInfoCollector.ProcInfo> procMap;
    private final StringBuilder out = new StringBuilder();
    private ProcInfoCollector.ProcInfo current = null;
    private boolean suppressSavesForBody = false;
    private int sSaveArea = 0;
    private int sAreaStart = 0;
    private int extraArgSpace = 0;
    private int goalNumArgs = 0;
    private int goalMaxCallArgs = 0;

    public CodeGenVisitor(Map<String, ProcInfoCollector.ProcInfo> procMap) {
        this.procMap = procMap;
    }

    private void emit(String s) { out.append('\t').append(s).append('\n'); }
    private void emitLabel(String label) { out.append(label).append(':').append('\n'); }

    private String regName(Reg r) {
        if (r == null) return null;
        if (r.f0.choice instanceof NodeToken) return ((NodeToken)r.f0.choice).tokenImage;
        return r.f0.choice.toString();
    }
    private String intVal(IntegerLiteral il) { return il.f0.tokenImage; }
    private String labelName(Label l) { return l.f0.tokenImage; }

    private int computeUsedSCount(syntaxtree.StmtList stmts) {
        if (stmts == null || stmts.f0 == null || !stmts.f0.present()) return 0;
        Set<String> sregs = new HashSet<>();
        for (Node node : stmts.f0.nodes) {
            if (node instanceof NodeSequence) {
                NodeSequence seq = (NodeSequence) node;
                for (int i = 0; i < seq.size(); i++) {
                    Node child = seq.elementAt(i);
                    if (child instanceof Stmt) {
                        String txt = child.toString();
                        for (int r = 0; r < 10; r++) {
                            String reg = "s" + r;
                            if (txt.contains(reg)) sregs.add(reg);
                        }
                    }
                }
            }
        }
        return sregs.size();
    }

    @Override
    public Void visit(Goal n) {
        // MAIN [numArgs] [stackSlots] [maxCallArgs]
        goalNumArgs = Integer.parseInt(n.f2.f0.tokenImage);
        int stackSlots = Integer.parseInt(n.f5.f0.tokenImage);
        goalMaxCallArgs = Integer.parseInt(n.f8.f0.tokenImage);
        int extraArgs = Math.max(0, goalMaxCallArgs - 4);
        emit(".text");
        emit(".globl\tmain");
        emitLabel("main");
        emit("move $fp, $sp");
        emit("sw $ra, -4($fp)");
        int alloc = 4 * stackSlots + 4 + 4 * extraArgs;
        emit("subu $sp, $sp, " + alloc);
        sAreaStart = 0;
        sSaveArea = computeUsedSCount(n.f10) * 4;
        extraArgSpace = 4 * extraArgs;
        n.f10.accept(this);
        emit("addu $sp, $sp, " + alloc);
        emit("lw $ra, -4($fp)");
        emit("j $ra");
        out.append('\n');  
        n.f13.accept(this);
        out.append('\n');  
        emit(".text");
        emit(".globl\t_halloc");
        out.append('\n');  
        emitLabel("_halloc");
        emit("li $v0, 9");
        emit("syscall");
        emit("j $ra");
        emit(".text");
        emit(".globl\t_print");
        emitLabel("_print");
        emit("li $v0, 1");
        emit("syscall");
        emit("la $a0, newl");
        emit("li $v0, 4");
        emit("syscall");
        emit("j $ra");
        emit(".data");
        emit(".align 0");
        out.append("newl:\t.asciiz \"\\n\"\n");
        out.append(".data\n.align 0\nstr_er:\t.asciiz \"ERROR: abnormal termination\\n\"\n");
        System.out.print(out.toString());
        return null;
    }

    @Override
    public Void visit(Procedure n) {
        String pname = n.f0.f0.tokenImage;
        ProcInfoCollector.ProcInfo pi = procMap.get(pname);
        current = pi;
        emit(".text");
        emit(".globl\t" + pname);
        emitLabel(pname);
        emit("sw $fp, -8($sp)");
        emit("move $fp, $sp");
        emit("sw $ra, -4($fp)");
        boolean bodyHasAstoreS = false;
        try {
            if (n.f10 != null && n.f10.f0 != null && n.f10.f0.present()) {
                for (Node node : n.f10.f0.nodes) {
                    if (node instanceof NodeSequence) {
                        NodeSequence seq = (NodeSequence) node;
                        for (int i = 0; i < seq.size(); i++) {
                            Node child = seq.elementAt(i);
                            if (child instanceof Stmt) {
                                Stmt s = (Stmt) child;
                                if (s.f0.choice instanceof AStoreStmt) {
                                    AStoreStmt as = (AStoreStmt) s.f0.choice;
                                    String reg = regName(as.f2);
                                    if (reg != null && reg.startsWith("s")) {
                                        bodyHasAstoreS = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    if (bodyHasAstoreS) break;
                }
            }
        } catch (Exception e) { bodyHasAstoreS = false; }
        List<String> sregs = new ArrayList<>(pi.usedS);
        Collections.sort(sregs);
        sSaveArea = 4 * sregs.size();
        sAreaStart = 0;  //0($sp)
        int extra = Math.max(0, pi.maxCallArgs - 4);
        int alloc = 4 * pi.stackSlots + 8 + 4 * extra;
        emit("subu $sp, $sp, " + alloc);
        extraArgSpace = 4 * extra;
        int offset = 0;
        for (String r : sregs) {
            emit("sw $" + r + ", " + (sAreaStart + offset) + "($sp)");
            offset += 4;
        }
        suppressSavesForBody = bodyHasAstoreS;
        n.f10.accept(this);
        offset = 0;
        for (String r : sregs) {
            emit("lw $" + r + ", " + (sAreaStart + offset) + "($sp)");
            offset += 4;
        }
        emit("addu $sp, $sp, " + alloc);
        emit("lw $ra, -4($fp)");
        emit("lw $fp, -8($sp)");
        emit("j $ra");
        out.append('\n');  
        current = null;
        return null;
    }

    @Override
    public Void visit(MoveStmt n) {
        String dst = regName(n.f1);
        String dstReg = "$" + dst;
        NodeChoice exp = n.f2.f0;
        if (exp.choice instanceof HAllocate) {
            HAllocate ha = (HAllocate) exp.choice;
            SimpleExp sizeExpr = ha.f1;
            if (sizeExpr.f0.choice instanceof IntegerLiteral) {
                String imm = intVal((IntegerLiteral)sizeExpr.f0.choice);
                emit("li $v1 " + imm);
                emit("move " + dstReg + " $v1");
                emit("move $a0 " + dstReg);
                emit("jal _halloc");
                emit("move " + dstReg + " $v0");
            } else if (sizeExpr.f0.choice instanceof Reg) {
                String r = regName((Reg)sizeExpr.f0.choice);
                emit("move $a0 $" + r);
                emit("jal _halloc");
                emit("move " + dstReg + " $v0");
            } else {
                emit("# unsupported HALLOCATE form");
            }
        } else if (exp.choice instanceof BinOp) {
            BinOp b = (BinOp) exp.choice;
            String op = ((NodeToken)b.f0.f0.choice).tokenImage;
            String left = regName(b.f1);
            String rightReg = null;
            if (b.f2.f0.choice instanceof Reg) rightReg = "$" + regName((Reg)b.f2.f0.choice);
            else if (b.f2.f0.choice instanceof IntegerLiteral) {
                String imm = intVal((IntegerLiteral)b.f2.f0.choice);
                emit("li $v1 " + imm);
                rightReg = "$v1";
            } else if (b.f2.f0.choice instanceof Label) {
                String lab = labelName((Label)b.f2.f0.choice);
                emit("la $v1 " + lab);
                rightReg = "$v1";
            }
            String leftReg = "$" + left;
            if (op.equals("LE")) {
                emit("sle " + dstReg + ", " + leftReg + ", " + rightReg);
            } else if (op.equals("NE")) {
                emit("sne " + dstReg + ", " + leftReg + ", " + rightReg);
            } else if (op.equals("PLUS")) {
                emit("addu " + dstReg + ", " + leftReg + ", " + rightReg);
            } else if (op.equals("MINUS")) {
                emit("sub " + dstReg + ", " + leftReg + ", " + rightReg);
            } else if (op.equals("TIMES")) {
                emit("mul " + dstReg + ", " + leftReg + ", " + rightReg);
            } else if (op.equals("DIV")) {
                emit("div " + leftReg + ", " + rightReg);
                emit("mflo " + dstReg);
            }
        } else if (exp.choice instanceof SimpleExp) {
            if (n.f2.f0.choice instanceof SimpleExp) {
                SimpleExp s = (SimpleExp) n.f2.f0.choice;
                if (s.f0.choice instanceof Reg) {
                    String r = regName((Reg)s.f0.choice);
                    emit("move " + dstReg + " $" + r);
                } else if (s.f0.choice instanceof IntegerLiteral) {
                    String imm = intVal((IntegerLiteral)s.f0.choice);
                    emit("li " + dstReg + " " + imm);
                } else if (s.f0.choice instanceof Label) {
                    String lab = labelName((Label)s.f0.choice);
                    emit("la " + dstReg + " " + lab);
                }
            }
        }
        return null;
    }

    @Override
    public Void visit(HStoreStmt n) {
        String base = regName(n.f1);
        String offset = intVal(n.f2);
        String src = regName(n.f3);
        emit("sw $" + src + ", " + offset + "($" + base + ")");
        return null;
    }

    @Override
    public Void visit(HLoadStmt n) {
        String dst = regName(n.f1);
        String base = regName(n.f2);
        String offset = intVal(n.f3);
        emit("lw $" + dst + ", " + offset + "($" + base + ")");
        return null;
    }

    @Override
    public Void visit(StmtList n) {
        if (n == null || n.f0 == null || !n.f0.present()) return null;
        for (Node node : n.f0.nodes) {
            if (node instanceof NodeSequence) {
                NodeSequence seq = (NodeSequence) node;
                String labelStr = null;
                if (seq.size() >= 1 && seq.elementAt(0) instanceof NodeOptional) {
                    NodeOptional opt = (NodeOptional) seq.elementAt(0);
                    if (opt.present() && opt.node instanceof Label) {
                        labelStr = labelName((Label)opt.node);
                    }
                }
                if (seq.size() >= 2 && seq.elementAt(1) instanceof Stmt) {
                    Stmt stmt = (Stmt) seq.elementAt(1);
                    if (labelStr != null) {
                        out.append(labelStr).append(":\t\t");  
                    }
                    stmt.accept(this);
                }
            }
        }
        return null;
    }

    @Override
    public Void visit(NoOpStmt n) {
        emit("nop");
        return null;
    }

    @Override
    public Void visit(ErrorStmt n) {
        emit("la $a0, str_er");
        emit("li $v0, 4");
        emit("syscall");
        emit("li $v0, 10");
        emit("syscall");
        return null;
    }

    @Override
    public Void visit(CJumpStmt n) {
        String r = regName(n.f1);
        String lab = labelName(n.f2);
        emit("beqz $" + r + " " + lab);
        return null;
    }

    @Override
    public Void visit(JumpStmt n) {
        String lab = labelName(n.f1);
        emit("b " + lab);
        return null;
    }

    @Override
    public Void visit(PrintStmt n) {
        SimpleExp se = n.f1;
        if (se.f0.choice instanceof Reg) {
            String r = regName((Reg)se.f0.choice);
            emit("move $a0 $" + r);
        } else if (se.f0.choice instanceof IntegerLiteral) {
            String imm = intVal((IntegerLiteral)se.f0.choice);
            emit("li $a0 " + imm);
        } else if (se.f0.choice instanceof Label) {
            String lab = labelName((Label)se.f0.choice);
            emit("la $a0 " + lab);
        }
        emit("jal _print");
        return null;
    }

    @Override
    public Void visit(AStoreStmt n) {
    int idx = Integer.parseInt(n.f1.f1.f0.tokenImage);
        String src = regName(n.f2);
        if (suppressSavesForBody) {
            if (src != null && src.startsWith("s")) return null;
        }
        if (current != null) {
            int numArgs = current.numArgs;
            int maxArgs = current.maxCallArgs;
            int incomingSlots = Math.max(0, numArgs - 4);
            if (idx < incomingSlots) {
                int byteOff = idx * 4;
                emit("sw $" + src + ", " + byteOff + "($fp)");
            } else {
                int passArgSpace = Math.max(0, maxArgs - 4) * 4;
                int localOffset = (idx - incomingSlots) * 4 + passArgSpace;
                emit("sw $" + src + ", " + localOffset + "($sp)");
            }
        } else {
            // in main, spilled slots - extraArgSpace + 4*idx
            int offset = extraArgSpace + 4 * idx;
            emit("sw $" + src + ", " + offset + "($sp)");
        }
        return null;
    }

    @Override
    public Void visit(ALoadStmt n) {
        String dst = regName(n.f1);
    int idx = Integer.parseInt(n.f2.f1.f0.tokenImage);
        if (suppressSavesForBody) {
            if (dst != null && dst.startsWith("s")) return null;
        }
        if (current != null) {
            int numArgs = current.numArgs;
            int maxArgs = current.maxCallArgs;
            int incomingSlots = Math.max(0, numArgs - 4);
            if (idx < incomingSlots) {
                int byteOff = idx * 4;
                emit("lw $" + dst + ", " + byteOff + "($fp)");
            } else {
                int passArgSpace = Math.max(0, maxArgs - 4) * 4;
                int localOffset = (idx - incomingSlots) * 4 + passArgSpace;
                emit("lw $" + dst + ", " + localOffset + "($sp)");
            }
        } else {
            // in main, spilled slots - extraArgSpace + 4*idx
            int offset = extraArgSpace + 4 * idx;
            emit("lw $" + dst + ", " + offset + "($sp)");
        }
        return null;
    }

    @Override
    public Void visit(PassArgStmt n) {
    int which = Integer.parseInt(n.f1.f0.tokenImage);
        String src = regName(n.f2);
        int offset = 4 * (which - 1);
        emit("sw $" + src + ", " + offset + "($sp)");
        return null;
    }

    @Override
    public Void visit(CallStmt n) {
        if (n.f1.f0.choice instanceof Reg) {
            String r = regName((Reg)n.f1.f0.choice);
            emit("jalr $" + r);
        } else if (n.f1.f0.choice instanceof Label) {
            String lab = labelName((Label)n.f1.f0.choice);
            emit("jal " + lab);
        }
        return null;
    }
}
