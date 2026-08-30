package visitor;

import java.util.*;
import syntaxtree.*;


public class ProcInfoCollector extends GJNoArguDepthFirst<Void> {
    public static class ProcInfo {
        public String name;
        public int numArgs;
        public int stackSlots;
        public int maxCallArgs;
        public boolean hasCall = false;
        public Set<String> usedS = new HashSet<>();
        public Set<String> usedT = new HashSet<>();
        public boolean spilled = false;
        public ProcInfo(String name) { this.name = name; }
    }

    private Map<String,ProcInfo> procMap = new LinkedHashMap<>();
    private ProcInfo current = null;

    public Map<String,ProcInfo> getProcMap() { return procMap; }

    public Void visit(Procedure n) {
        String pname = n.f0.f0.tokenImage;
        ProcInfo pi = new ProcInfo(pname);
        pi.numArgs = Integer.parseInt(n.f2.f0.tokenImage);
        pi.stackSlots = Integer.parseInt(n.f5.f0.tokenImage);
        pi.maxCallArgs = Integer.parseInt(n.f8.f0.tokenImage);
        procMap.put(pname, pi);
        current = pi;
        n.f10.accept(this);
        if (n.f12 != null) {
            if (n.f12.present()) {
                Node spill = n.f12.node;
                if (spill != null) {
                    try {
                        Object choice = ((syntaxtree.SpillInfo)spill).f1.f0.choice;
                        if (choice instanceof syntaxtree.NodeToken) {
                            String tok = ((syntaxtree.NodeToken)choice).tokenImage;
                            if (tok.equals("SPILLED")) pi.spilled = true;
                            else pi.spilled = false;
                        }
                    } catch (Exception e) { }
                }
            }
        }
        current = null;
        return null;
    }

    public Void visit(CallStmt n) {
        if (current != null) current.hasCall = true;
        return super.visit(n);
    }

    public Void visit(Reg n) {
        if (current == null) return null;
        Object choice = n.f0.choice;
        String r = null;
        if (choice instanceof syntaxtree.NodeToken) r = ((syntaxtree.NodeToken)choice).tokenImage;
        else r = choice.toString();
        if (r.startsWith("s")) current.usedS.add(r);
        if (r.startsWith("t")) current.usedT.add(r);
        return null;
    }

    public Void visit(SimpleExp n) { return n.f0.accept(this); }

}
