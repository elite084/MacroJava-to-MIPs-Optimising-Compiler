package visitor;

import java.util.*;
import syntaxtree.*;

/**
 * Pass 1: Liveness Analysis
 * Computes live ranges for all temporaries in the program
 */
public class LivenessAnalyzer extends GJNoArguDepthFirst<Set<String>> {
    
    // Map from procedure name to its liveness info
    private final Map<String, ProcedureLivenessInfo> procedureLiveness;
    private String currentProcedure;
    private int stmtCounter;
    
    public LivenessAnalyzer() {
        this.procedureLiveness = new HashMap<>();
        this.currentProcedure = "MAIN";
        this.stmtCounter = 0;
    }
    
    public Map<String, ProcedureLivenessInfo> getProcedureLiveness() {
        return procedureLiveness;
    }
    
    @Override
    public Set<String> visit(Goal n) {
        // Process MAIN
        currentProcedure = "MAIN";
        ProcedureLivenessInfo mainInfo = new ProcedureLivenessInfo("MAIN");
        procedureLiveness.put("MAIN", mainInfo);
        stmtCounter = 0;
        
        n.f1.accept(this); // StmtList in MAIN
        
        // Process other procedures
        n.f3.accept(this); // NodeListOptional of procedures
        
        // Compute liveness for all procedures
        for (ProcedureLivenessInfo info : procedureLiveness.values()) {
            info.computeLiveness();
        }
        
        return new HashSet<>();
    }
    
    @Override
    public Set<String> visit(Procedure n) {
        String label = n.f0.f0.tokenImage;
        currentProcedure = label;
        
        ProcedureLivenessInfo procInfo = new ProcedureLivenessInfo(label);
        
        // Get number of parameters
        int numParams = Integer.parseInt(n.f2.f0.tokenImage);
        procInfo.setNumParams(numParams);
        
        procedureLiveness.put(label, procInfo);
        stmtCounter = 0;
        
        n.f4.f1.accept(this); // StmtList within BEGIN...END
        
        // Handle RETURN - add the return expression as a use in a pseudo-statement
        // This ensures the return value is live at procedure exit
        int returnStmt = stmtCounter++;
        Set<String> returnUse = getSimpleExpUse(n.f4.f3);
        Set<String> returnDef = new HashSet<>();
        procInfo.addStatement(returnStmt, returnUse, returnDef, null, false);
        
        return new HashSet<>();
    }
    
    @Override
    public Set<String> visit(Stmt n) {
        ProcedureLivenessInfo info = procedureLiveness.get(currentProcedure);
        int currentStmt = stmtCounter++;
        
        Set<String> use = new HashSet<>();
        Set<String> def = new HashSet<>();
        String jumpTarget = null;
        boolean isCJump = false;
        
        // Visit the statement and collect use/def
        if (n.f0.which == 4) { // HStoreStmt
            HStoreStmt hstore = (HStoreStmt) n.f0.choice;
            use.add(getTempName(hstore.f1));
            use.add(getTempName(hstore.f3));
        } else if (n.f0.which == 5) { // HLoadStmt
            HLoadStmt hload = (HLoadStmt) n.f0.choice;
            def.add(getTempName(hload.f1) + "#" + currentStmt); // Make def unique
            use.add(getTempName(hload.f2));
        } else if (n.f0.which == 6) { // MoveStmt
            MoveStmt move = (MoveStmt) n.f0.choice;
            def.add(getTempName(move.f1) + "#" + currentStmt); // Make def unique
            Set<String> expUse = getExpUse(move.f2);
            use.addAll(expUse);
        } else if (n.f0.which == 7) { // PrintStmt
            PrintStmt print = (PrintStmt) n.f0.choice;
            Set<String> simpleUse = getSimpleExpUse(print.f1);
            use.addAll(simpleUse);
        } else if (n.f0.which == 2) { // CJumpStmt
            CJumpStmt cjump = (CJumpStmt) n.f0.choice;
            use.add(getTempName(cjump.f1));
            // Get jump target label
            jumpTarget = cjump.f2.f0.tokenImage;
            isCJump = true;
        } else if (n.f0.which == 3) { // JumpStmt
            JumpStmt jump = (JumpStmt) n.f0.choice;
            // Get jump target label
            jumpTarget = jump.f1.f0.tokenImage;
        }
        
        info.addStatement(currentStmt, use, def, jumpTarget, isCJump);
        
        return new HashSet<>();
    }
    
    @Override
    public Set<String> visit(Label n) {
        // Record label position
        ProcedureLivenessInfo info = procedureLiveness.get(currentProcedure);
        String labelName = n.f0.tokenImage;
        info.addLabel(labelName, stmtCounter);
        return new HashSet<>();
    }
    
    private String getTempName(Temp temp) {
        return "TEMP " + temp.f1.f0.tokenImage;
    }
    
    private Set<String> getExpUse(Exp exp) {
        Set<String> use = new HashSet<>();
        
        if (exp.f0.which == 0) { // Call
            Call call = (Call) exp.f0.choice;
            Set<String> callUse = getSimpleExpUse(call.f1);
            use.addAll(callUse);
            for (Node temp : call.f3.nodes) {
                use.add(getTempName((Temp) temp));
            }
        } else if (exp.f0.which == 1) { // HAllocate
            HAllocate halloc = (HAllocate) exp.f0.choice;
            use.addAll(getSimpleExpUse(halloc.f1));
        } else if (exp.f0.which == 2) { // BinOp
            BinOp binop = (BinOp) exp.f0.choice;
            use.add(getTempName(binop.f1));
            use.addAll(getSimpleExpUse(binop.f2));
        } else if (exp.f0.which == 3) { // SimpleExp
            use.addAll(getSimpleExpUse((SimpleExp) exp.f0.choice));
        }
        
        return use;
    }
    
    private Set<String> getSimpleExpUse(SimpleExp sexp) {
        Set<String> use = new HashSet<>();
        
        if (sexp.f0.which == 0) { // Temp
            use.add(getTempName((Temp) sexp.f0.choice));
        }
        // IntegerLiteral and Label don't use temps
        
        return use;
    }
    
    /**
     * Holds liveness information for a single procedure
     */
    public static class ProcedureLivenessInfo {
        private String name;
        private int numParams;
        private List<StatementInfo> statements;
        private Map<String, Integer> labelToStmt; // Maps label names to statement indices
        private Map<Integer, Set<String>> liveIn;
        private Map<Integer, Set<String>> liveOut;
        private Map<String, LiveRange> liveRanges;
        // Maps statement index -> (temp name -> set of reaching definition statements)
        private Map<Integer, Map<String, Set<Integer>>> stmtToMergePoint;
        
        public ProcedureLivenessInfo(String name) {
            this.name = name;
            this.statements = new ArrayList<>();
            this.labelToStmt = new HashMap<>();
            this.liveIn = new HashMap<>();
            this.liveOut = new HashMap<>();
            this.liveRanges = new HashMap<>();
            this.stmtToMergePoint = new HashMap<>();
        }
        
        public void setNumParams(int numParams) {
            this.numParams = numParams;
        }
        
        public int getNumParams() {
            return numParams;
        }
        
        public Map<Integer, Map<String, Set<Integer>>> getMergePoints() {
            return stmtToMergePoint;
        }
        
        public void addLabel(String label, int stmtIndex) {
            labelToStmt.put(label, stmtIndex);
        }
        
        public void addStatement(int index, Set<String> use, Set<String> def, String jumpTarget, boolean isCJump) {
            statements.add(new StatementInfo(index, use, def, jumpTarget, isCJump));
        }
        
        private void fixupUses() {
            // Build a map of which statement defines each temp
            Map<String, List<Integer>> tempDefs = new HashMap<>();
            for (StatementInfo stmt : statements) {
                for (String def : stmt.def) {
                    String baseName;
                    if (def.contains("#")) {
                        baseName = def.substring(0, def.lastIndexOf('#'));
                    } else {
                        // Parameter without version
                        baseName = def;
                    }
                    tempDefs.putIfAbsent(baseName, new ArrayList<>());
                    tempDefs.get(baseName).add(stmt.index);
                }
            }
            
            // Compute reaching definitions using data flow analysis
            Map<Integer, Map<String, Set<Integer>>> reachingDefs = computeReachingDefinitions(tempDefs);
            
            // Now fix up uses based on reaching definitions
            for (StatementInfo stmt : statements) {
                Set<String> newUse = new HashSet<>();
                Map<String, Set<Integer>> reaching = reachingDefs.get(stmt.index);
                
                for (String use : stmt.use) {
                    Set<Integer> defs = reaching.get(use);
                    if (defs != null && defs.size() == 1) {
                        // Single reaching definition
                        int defStmt = defs.iterator().next();
                        newUse.add(use + "#" + defStmt);
                    } else if (defs != null && defs.size() > 1) {
                        // Multiple reaching definitions - pick the latest one
                        // This represents the value from the loop back edge
                        int maxDef = Collections.max(defs);
                        newUse.add(use + "#" + maxDef);
                        
                        // Store merge point info for register allocator
                        // All reaching defs must get different registers
                        stmtToMergePoint.putIfAbsent(stmt.index, new HashMap<>());
                        stmtToMergePoint.get(stmt.index).put(use, defs);
                    } else {
                        // No definition found - keep original (parameter or constant)
                        newUse.add(use);
                    }
                }
                stmt.use = newUse;
            }
        }
        
        private Map<Integer, Map<String, Set<Integer>>> computeReachingDefinitions(Map<String, List<Integer>> tempDefs) {
            Map<Integer, Map<String, Set<Integer>>> reachingDefs = new HashMap<>();
            
            // Initialize
            for (StatementInfo stmt : statements) {
                reachingDefs.put(stmt.index, new HashMap<>());
            }
            
            // Iterate to fixed point
            boolean changed = true;
            int iterations = 0;
            while (changed && iterations < 100) {
                changed = false;
                iterations++;
                
                for (StatementInfo stmt : statements) {
                    Map<String, Set<Integer>> oldReaching = new HashMap<>();
                    for (Map.Entry<String, Set<Integer>> entry : reachingDefs.get(stmt.index).entrySet()) {
                        oldReaching.put(entry.getKey(), new HashSet<>(entry.getValue()));
                    }
                    
                    Map<String, Set<Integer>> newReaching = new HashMap<>();
                    
                    // Get predecessors
                    List<Integer> preds = getPredecessors(stmt.index);
                    for (int pred : preds) {
                        Map<String, Set<Integer>> predOut = new HashMap<>(reachingDefs.get(pred));
                        
                        // Apply gen/kill for predecessor's definitions
                        StatementInfo predStmt = statements.get(findStmtIndex(pred));
                        for (String def : predStmt.def) {
                            String baseName;
                            if (def.contains("#")) {
                                baseName = def.substring(0, def.lastIndexOf('#'));
                            } else {
                                // Parameter without version
                                baseName = def;
                            }
                            // Kill other defs of same base temp
                            predOut.put(baseName, new HashSet<>(Collections.singleton(pred)));
                        }
                        
                        // Merge with new reaching
                        for (Map.Entry<String, Set<Integer>> entry : predOut.entrySet()) {
                            newReaching.putIfAbsent(entry.getKey(), new HashSet<>());
                            newReaching.get(entry.getKey()).addAll(entry.getValue());
                        }
                    }
                    
                    if (!mapsEqual(newReaching, oldReaching)) {
                        reachingDefs.put(stmt.index, newReaching);
                        changed = true;
                    }
                }
            }
            
            return reachingDefs;
        }
        
        private List<Integer> getPredecessors(int stmtIndex) {
            List<Integer> preds = new ArrayList<>();
            int idx = findStmtIndex(stmtIndex);
            
            // Check if previous statement falls through
            if (idx > 0) {
                StatementInfo prevStmt = statements.get(idx - 1);
                if (prevStmt.jumpTarget != null) {
                    // Previous is a jump
                    if (prevStmt.isCJump) {
                        // Conditional jump - can fall through
                        preds.add(prevStmt.index);
                    }
                    // Unconditional jump - no fall through
                } else {
                    // Normal statement - falls through
                    preds.add(prevStmt.index);
                }
            }
            
            // Check if any jumps target this statement
            for (StatementInfo stmt : statements) {
                if (stmt.jumpTarget != null) {
                    Integer targetIdx = labelToStmt.get(stmt.jumpTarget);
                    if (targetIdx != null && targetIdx == stmtIndex) {
                        preds.add(stmt.index);
                    }
                }
            }
            
            return preds;
        }
        
        private int findStmtIndex(int stmtNumber) {
            for (int i = 0; i < statements.size(); i++) {
                if (statements.get(i).index == stmtNumber) {
                    return i;
                }
            }
            return -1;
        }
        
        private boolean mapsEqual(Map<String, Set<Integer>> m1, Map<String, Set<Integer>> m2) {
            if (m1.size() != m2.size()) return false;
            for (Map.Entry<String, Set<Integer>> entry : m1.entrySet()) {
                if (!m2.containsKey(entry.getKey())) return false;
                if (!entry.getValue().equals(m2.get(entry.getKey()))) return false;
            }
            return true;
        }
        
        public void computeLiveness() {
            // First fix up uses to reference correct definitions
            fixupUses();
            
            // Initialize
            for (StatementInfo stmt : statements) {
                liveIn.put(stmt.index, new HashSet<>());
                liveOut.put(stmt.index, new HashSet<>());
            }
            
            // Iterate until fixed point
            boolean changed = true;
            while (changed) {
                changed = false;
                
                // Process statements in reverse order
                for (int i = statements.size() - 1; i >= 0; i--) {
                    StatementInfo stmt = statements.get(i);
                    
                    Set<String> oldIn = new HashSet<>(liveIn.get(stmt.index));
                    Set<String> oldOut = new HashSet<>(liveOut.get(stmt.index));
                    
                    // out[i] = union of in[j] for all successors j
                    Set<String> newOut = new HashSet<>();
                    
                    // Determine successors based on control flow
                    if (stmt.jumpTarget != null) {
                        // This is a jump or cjump
                        Integer targetIdx = labelToStmt.get(stmt.jumpTarget);
                        if (targetIdx != null) {
                            newOut.addAll(liveIn.get(targetIdx));
                        }
                        
                        // If it's a conditional jump, also include fall-through
                        if (stmt.isCJump && i + 1 < statements.size()) {
                            newOut.addAll(liveIn.get(statements.get(i + 1).index));
                        }
                    } else {
                        // Regular statement: successor is next statement
                        if (i + 1 < statements.size()) {
                            newOut.addAll(liveIn.get(statements.get(i + 1).index));
                        }
                    }
                    
                    // in[i] = use[i] union (out[i] - def[i])
                    Set<String> newIn = new HashSet<>(stmt.use);
                    Set<String> temp = new HashSet<>(newOut);
                    temp.removeAll(stmt.def);
                    newIn.addAll(temp);
                    
                    liveIn.put(stmt.index, newIn);
                    liveOut.put(stmt.index, newOut);
                    
                    if (!newIn.equals(oldIn) || !newOut.equals(oldOut)) {
                        changed = true;
                    }
                }
            }
            
            // Compute live ranges
            computeLiveRanges();
        }
        
        private void computeLiveRanges() {
            for (int i = 0; i < statements.size(); i++) {
                Set<String> live = liveIn.get(i);
                for (String temp : live) {
                    if (!liveRanges.containsKey(temp)) {
                        liveRanges.put(temp, new LiveRange(temp));
                    }
                    liveRanges.get(temp).addPoint(i);
                }
                
                live = liveOut.get(i);
                for (String temp : live) {
                    if (!liveRanges.containsKey(temp)) {
                        liveRanges.put(temp, new LiveRange(temp));
                    }
                    liveRanges.get(temp).addPoint(i);
                }
            }
        }
        
        public Map<String, LiveRange> getLiveRanges() {
            return liveRanges;
        }
        
        public List<StatementInfo> getStatements() {
            return statements;
        }
        
        public Set<String> getLiveOut(int stmtIndex) {
            return liveOut.getOrDefault(stmtIndex, new HashSet<>());
        }
        
        public static class StatementInfo {
            int index;
            Set<String> use;
            Set<String> def;
            String jumpTarget;  // null for non-jumps, label name for jumps
            boolean isCJump;    // true for conditional jumps
            
            StatementInfo(int index, Set<String> use, Set<String> def, String jumpTarget, boolean isCJump) {
                this.index = index;
                this.use = use;
                this.def = def;
                this.jumpTarget = jumpTarget;
                this.isCJump = isCJump;
            }
            
            public Set<String> getUse() {
                return use;
            }
            
            public Set<String> getDef() {
                return def;
            }
        }
    }
    
    public static class LiveRange {
        String temp;
        int start;
        int end;
        Set<Integer> points;
        
        public LiveRange(String temp) {
            this.temp = temp;
            this.start = Integer.MAX_VALUE;
            this.end = Integer.MIN_VALUE;
            this.points = new HashSet<>();
        }
        
        public void addPoint(int point) {
            points.add(point);
            start = Math.min(start, point);
            end = Math.max(end, point);
        }
        
        public boolean interferesWith(LiveRange other) {
            // Two ranges interfere if they overlap
            for (int point : points) {
                if (other.points.contains(point)) {
                    return true;
                }
            }
            return false;
        }
        
        public String getTemp() {
            return temp;
        }
    }
}
