package visitor;

import java.util.*;

/**
 * Pass 2: Register Allocation
 * Builds interference graph and performs graph coloring
 */
public class RegisterAllocator {
    
    // Available registers for allocation (NOT v0/v1 - those are reserved)
    private static final String[] S_REGISTERS = {"s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7"};
    private static final String[] T_REGISTERS = {"t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7", "t8", "t9"};
    
    private Map<String, AllocationInfo> procedureAllocation;
    
    public RegisterAllocator() {
        this.procedureAllocation = new HashMap<>();
    }
    
    public Map<String, AllocationInfo> getProcedureAllocation() {
        return procedureAllocation;
    }
    
    public void allocateRegisters(Map<String, LivenessAnalyzer.ProcedureLivenessInfo> livenessInfo) {
        for (Map.Entry<String, LivenessAnalyzer.ProcedureLivenessInfo> entry : livenessInfo.entrySet()) {
            String procName = entry.getKey();
            LivenessAnalyzer.ProcedureLivenessInfo info = entry.getValue();
            
            AllocationInfo allocInfo = allocateForProcedure(info);
            procedureAllocation.put(procName, allocInfo);
        }
    }
    
    private AllocationInfo allocateForProcedure(LivenessAnalyzer.ProcedureLivenessInfo livenessInfo) {
        AllocationInfo allocInfo = new AllocationInfo();
        
        Map<String, LivenessAnalyzer.LiveRange> liveRanges = livenessInfo.getLiveRanges();
        int numParams = livenessInfo.getNumParams();
        
        // Pre-assign parameters to dedicated registers
        // Parameters (TEMP 0, TEMP 1, etc.) need fixed allocations to prevent register reuse
        Set<String> precoloredTemps = new HashSet<>();
        Set<String> reservedRegs = new HashSet<>();
        
        for (int i = 0; i < numParams && i < 4; i++) {
            String paramTemp = "TEMP " + i;
            if (liveRanges.containsKey(paramTemp)) {
                // Assign parameter to a dedicated s-register
                // Use s0-s3 for first 4 parameters
                String dedicatedReg = "s" + i;
                allocInfo.assignRegister(paramTemp, dedicatedReg);
                precoloredTemps.add(paramTemp);
                reservedRegs.add(dedicatedReg);
            }
        }
        
        // Build interference graph (excluding precolored parameters)
        InterferenceGraph graph = new InterferenceGraph();
        for (LivenessAnalyzer.LiveRange range : liveRanges.values()) {
            if (!precoloredTemps.contains(range.getTemp())) {
                graph.addNode(range.getTemp());
            }
        }
        
        // Add edges for interfering temporaries
        List<LivenessAnalyzer.LiveRange> ranges = new ArrayList<>(liveRanges.values());
        for (int i = 0; i < ranges.size(); i++) {
            for (int j = i + 1; j < ranges.size(); j++) {
                String temp1 = ranges.get(i).getTemp();
                String temp2 = ranges.get(j).getTemp();
                
                // Skip if either is precolored
                if (precoloredTemps.contains(temp1) || precoloredTemps.contains(temp2)) {
                    continue;
                }
                
                if (ranges.get(i).interferesWith(ranges.get(j))) {
                    graph.addEdge(temp1, temp2);
                }
            }
        }
        
        // Add interference edges for merge points (phi-functions)
        // All reaching definitions at a merge point must get different registers
        Map<Integer, Map<String, Set<Integer>>> mergePoints = livenessInfo.getMergePoints();
        for (Map.Entry<Integer, Map<String, Set<Integer>>> entry : mergePoints.entrySet()) {
            for (Map.Entry<String, Set<Integer>> tempEntry : entry.getValue().entrySet()) {
                String baseName = tempEntry.getKey();
                Set<Integer> reachingDefs = tempEntry.getValue();
                
                // Add edges between all pairs of reaching definitions
                List<Integer> defsList = new ArrayList<>(reachingDefs);
                for (int i = 0; i < defsList.size(); i++) {
                    for (int j = i + 1; j < defsList.size(); j++) {
                        String temp1 = baseName + "#" + defsList.get(i);
                        String temp2 = baseName + "#" + defsList.get(j);
                        
                        // Only add edge if both temps are in the graph
                        if (graph.getNodes().contains(temp1) && graph.getNodes().contains(temp2)) {
                            graph.addEdge(temp1, temp2);
                        }
                    }
                }
            }
        }
        
        // Build list of available registers (excluding reserved ones)
        List<String> availableRegs = new ArrayList<>();
        for (String reg : S_REGISTERS) {
            if (!reservedRegs.contains(reg)) {
                availableRegs.add(reg);
            }
        }
        for (String reg : T_REGISTERS) {
            availableRegs.add(reg);
        }
        
        // Perform graph coloring
        Map<String, String> coloring = colorGraph(graph, availableRegs.size());
        
        // Map colors to actual registers
        for (Map.Entry<String, String> entry : coloring.entrySet()) {
            String temp = entry.getKey();
            int colorIndex = Integer.parseInt(entry.getValue());
            
            if (colorIndex < availableRegs.size()) {
                String reg = availableRegs.get(colorIndex);
                allocInfo.assignRegister(temp, reg);
            } else {
                // Spill to stack
                allocInfo.spillTemp(temp);
            }
        }
        
        return allocInfo;
    }
    
    private Map<String, String> colorGraph(InterferenceGraph graph, int maxColors) {
        Map<String, String> coloring = new HashMap<>();
        Set<String> colored = new HashSet<>();
        Stack<String> stack = new Stack<>();
        Set<String> nodes = new HashSet<>(graph.getNodes());
        
        // Simplification: remove nodes with degree < maxColors
        while (!nodes.isEmpty()) {
            String node = null;
            
            // Find node with degree < maxColors
            for (String n : nodes) {
                int degree = 0;
                for (String neighbor : graph.getNeighbors(n)) {
                    if (nodes.contains(neighbor)) {
                        degree++;
                    }
                }
                if (degree < maxColors) {
                    node = n;
                    break;
                }
            }
            
            // If no such node, pick one with minimum degree (spill candidate)
            if (node == null) {
                int minDegree = Integer.MAX_VALUE;
                for (String n : nodes) {
                    int degree = graph.getNeighbors(n).size();
                    if (degree < minDegree) {
                        minDegree = degree;
                        node = n;
                    }
                }
            }
            
            stack.push(node);
            nodes.remove(node);
        }
        
        // Coloring: assign colors
        while (!stack.isEmpty()) {
            String node = stack.pop();
            Set<String> usedColors = new HashSet<>();
            
            for (String neighbor : graph.getNeighbors(node)) {
                if (coloring.containsKey(neighbor)) {
                    usedColors.add(coloring.get(neighbor));
                }
            }
            
            // Find first available color
            for (int i = 0; i < maxColors + 100; i++) { // Allow spills
                if (!usedColors.contains(String.valueOf(i))) {
                    coloring.put(node, String.valueOf(i));
                    break;
                }
            }
        }
        
        return coloring;
    }

    /**
     * Unify all versions of the same base temp to use the same color.
     * For example, if TEMP 32#76 and TEMP 32#112 have different colors,
     * this will make them both use the same color (picking the lower one to prefer s-registers).
     */
    private Map<String, String> unifyVersionedTemps(Map<String, String> coloring) {
        Map<String, String> unified = new HashMap<>(coloring);
        
        // Group temps by base name (e.g., "TEMP 32#76" -> "TEMP 32")
        Map<String, List<String>> baseToVersions = new HashMap<>();
        for (String temp : coloring.keySet()) {
            String baseName;
            if (temp.contains("#")) {
                baseName = temp.substring(0, temp.indexOf('#'));
            } else {
                baseName = temp;
            }
            baseToVersions.putIfAbsent(baseName, new ArrayList<>());
            baseToVersions.get(baseName).add(temp);
        }
        
        // For each base temp with multiple versions, unify them to the lowest color
        for (Map.Entry<String, List<String>> entry : baseToVersions.entrySet()) {
            List<String> versions = entry.getValue();
            if (versions.size() > 1) {
                // Find the minimum color among all versions
                int minColor = Integer.MAX_VALUE;
                for (String version : versions) {
                    int color = Integer.parseInt(coloring.get(version));
                    minColor = Math.min(minColor, color);
                }
                
                // Assign all versions to the minimum color
                String minColorStr = String.valueOf(minColor);
                for (String version : versions) {
                    unified.put(version, minColorStr);
                }
            }
        }
        
        return unified;
    }

    /**
     * Remove interference edges between different versions of the same base temp.
     * This allows them to be assigned to the same register (coalescing).
     */
    private void removeIntraVersionInterferences(InterferenceGraph graph, 
                                                  Map<String, LivenessAnalyzer.LiveRange> liveRanges) {
        // Group temps by base name
        Map<String, List<String>> baseToVersions = new HashMap<>();
        for (String temp : graph.getNodes()) {
            String baseName;
            if (temp.contains("#")) {
                baseName = temp.substring(0, temp.indexOf('#'));
            } else {
                baseName = temp;
            }
            baseToVersions.putIfAbsent(baseName, new ArrayList<>());
            baseToVersions.get(baseName).add(temp);
        }
        
        // For each base temp with multiple versions, remove edges between them
        for (List<String> versions : baseToVersions.values()) {
            if (versions.size() > 1) {
                for (int i = 0; i < versions.size(); i++) {
                    for (int j = i + 1; j < versions.size(); j++) {
                        graph.removeEdge(versions.get(i), versions.get(j));
                    }
                }
            }
        }
    }

    /**
     * Color graph with priority for certain temps (parameters).
     * Priority temps are colored first to get preference for lower-numbered registers (s-registers).
     */
    private Map<String, String> colorGraphWithPriority(InterferenceGraph graph, int maxColors, List<String> priorityTemps) {
        Map<String, String> coloring = new HashMap<>();
        Set<String> remainingNodes = new HashSet<>(graph.getNodes());
        
        // Phase 1: Color priority temps first
        List<String> priorityStack = new ArrayList<>();
        for (String temp : priorityTemps) {
            if (remainingNodes.contains(temp)) {
                priorityStack.add(temp);
                remainingNodes.remove(temp);
            }
        }
        
        // Color priority temps - they get first pick of s-registers
        for (String node : priorityStack) {
            Set<String> usedColors = new HashSet<>();
            
            for (String neighbor : graph.getNeighbors(node)) {
                if (coloring.containsKey(neighbor)) {
                    usedColors.add(coloring.get(neighbor));
                }
            }
            
            // Find first available color (prefer low numbers = s-registers)
            for (int i = 0; i < maxColors + 100; i++) {
                if (!usedColors.contains(String.valueOf(i))) {
                    coloring.put(node, String.valueOf(i));
                    break;
                }
            }
        }
        
        // Phase 2: Color remaining temps using standard graph coloring
        Stack<String> stack = new Stack<>();
        Set<String> nodes = new HashSet<>(remainingNodes);
        
        // Simplification: remove nodes with degree < maxColors
        while (!nodes.isEmpty()) {
            String node = null;
            
            // Find node with degree < maxColors
            for (String n : nodes) {
                int degree = 0;
                for (String neighbor : graph.getNeighbors(n)) {
                    if (nodes.contains(neighbor)) {
                        degree++;
                    }
                }
                if (degree < maxColors) {
                    node = n;
                    break;
                }
            }
            
            // If no such node, pick one with minimum degree (spill candidate)
            if (node == null) {
                int minDegree = Integer.MAX_VALUE;
                for (String n : nodes) {
                    int degree = graph.getNeighbors(n).size();
                    if (degree < minDegree) {
                        minDegree = degree;
                        node = n;
                    }
                }
            }
            
            stack.push(node);
            nodes.remove(node);
        }
        
        // Coloring: assign colors to remaining temps
        while (!stack.isEmpty()) {
            String node = stack.pop();
            Set<String> usedColors = new HashSet<>();
            
            for (String neighbor : graph.getNeighbors(node)) {
                if (coloring.containsKey(neighbor)) {
                    usedColors.add(coloring.get(neighbor));
                }
            }
            
            // Find first available color
            for (int i = 0; i < maxColors + 100; i++) {
                if (!usedColors.contains(String.valueOf(i))) {
                    coloring.put(node, String.valueOf(i));
                    break;
                }
            }
        }
        
        return coloring;
    }
    
    /**
     * Interference Graph
     */
    private static class InterferenceGraph {
        private Map<String, Set<String>> adjacencyList;
        
        public InterferenceGraph() {
            this.adjacencyList = new HashMap<>();
        }
        
        public void addNode(String node) {
            adjacencyList.putIfAbsent(node, new HashSet<>());
        }
        
        public void addEdge(String node1, String node2) {
            adjacencyList.get(node1).add(node2);
            adjacencyList.get(node2).add(node1);
        }
        
        public void removeEdge(String node1, String node2) {
            if (adjacencyList.containsKey(node1)) {
                adjacencyList.get(node1).remove(node2);
            }
            if (adjacencyList.containsKey(node2)) {
                adjacencyList.get(node2).remove(node1);
            }
        }
        
        public Set<String> getNodes() {
            return adjacencyList.keySet();
        }
        
        public Set<String> getNeighbors(String node) {
            return adjacencyList.getOrDefault(node, new HashSet<>());
        }
    }
    
    /**
     * Holds allocation information for a procedure
     */
    public static class AllocationInfo {
        private Map<String, String> tempToRegister;
        private Map<String, Integer> tempToStackSlot;
        private int nextStackSlot;
        private boolean hasSpills;
        
        public AllocationInfo() {
            this.tempToRegister = new HashMap<>();
            this.tempToStackSlot = new HashMap<>();
            this.nextStackSlot = 0;
            this.hasSpills = false;
        }
        
        public void assignRegister(String temp, String register) {
            tempToRegister.put(temp, register);
        }
        
        public void spillTemp(String temp) {
            tempToStackSlot.put(temp, nextStackSlot++);
            hasSpills = true;
        }
        
        public String getRegister(String temp) {
            return tempToRegister.get(temp);
        }
        
        public Integer getStackSlot(String temp) {
            return tempToStackSlot.get(temp);
        }
        
        public boolean isSpilled(String temp) {
            return tempToStackSlot.containsKey(temp);
        }
        
        public int getNumStackSlots() {
            return nextStackSlot;
        }
        
        public boolean hasSpills() {
            return hasSpills;
        }
        
        public Set<String> getAllAllocatedRegisters() {
            return new HashSet<>(tempToRegister.values());
        }
        
        public Set<String> getAllTemps() {
            Set<String> allTemps = new HashSet<>();
            allTemps.addAll(tempToRegister.keySet());
            allTemps.addAll(tempToStackSlot.keySet());
            return allTemps;
        }
    }
}
