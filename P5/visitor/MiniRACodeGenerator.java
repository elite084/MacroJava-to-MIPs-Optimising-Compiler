package visitor;

import java.util.*;
import syntaxtree.*;

/**
 * Pass 3: Code Generation
 * Generates miniRA code from microIR with register allocation
 */
public class MiniRACodeGenerator extends GJNoArguDepthFirst<String> {
    
    private static final boolean DEBUG = false; // Set to true to enable debug output
    
    private Map<String, LivenessAnalyzer.ProcedureLivenessInfo> livenessInfo;
    private Map<String, RegisterAllocator.AllocationInfo> allocationInfo;
    private String currentProcedure;
    private StringBuilder output;
    private Map<String, Integer> procedureMaxArgs;
    private int currentMaxArgs;
    private Map<String, Integer> procedureSavedRegsCount; // Track s-registers per procedure
    private Map<String, Integer> procedureCallerSavedRegsCount; // Track t-registers per procedure
    private Map<String, Integer> procedureStackParams; // Track incoming stack parameters per procedure
    private Map<String, List<String>> procedureUsedSRegs; // Lists of used s-registers per procedure
    private Map<String, List<String>> procedureUsedTRegs; // Lists of used t-registers per procedure
    private int currentStmt; // Track current statement number for versioned temps
    private Map<String, Integer> mostRecentDef; // Track most recent defining statement for each temp
    private Map<String, String> stmtUseMap; // Map from "TEMP X" at current stmt to versioned name
    
    public MiniRACodeGenerator(
            Map<String, LivenessAnalyzer.ProcedureLivenessInfo> livenessInfo,
            Map<String, RegisterAllocator.AllocationInfo> allocationInfo) {
        this.livenessInfo = livenessInfo;
        this.allocationInfo = allocationInfo;
        this.output = new StringBuilder();
        this.procedureMaxArgs = new HashMap<>();
        this.currentMaxArgs = 0;
        this.procedureSavedRegsCount = new HashMap<>();
        this.procedureCallerSavedRegsCount = new HashMap<>();
        this.procedureStackParams = new HashMap<>();
        this.procedureUsedSRegs = new HashMap<>();
        this.procedureUsedTRegs = new HashMap<>();
        this.currentStmt = 0;
        this.mostRecentDef = new HashMap<>();
        this.stmtUseMap = new HashMap<>();
    }
    
    public String getGeneratedCode() {
        return output.toString();
    }
    
    private String getReg(String temp) {
        RegisterAllocator.AllocationInfo info = allocationInfo.get(currentProcedure);
        
        if(DEBUG && temp.equals("TEMP 32")) {
            System.err.println("DEBUG getReg(" + temp + ") at stmt " + currentStmt);
            System.err.println("  stmtUseMap key = " + currentStmt + ":" + temp);
        }
        
        // First, try stmtUseMap (from liveness analysis)
        String key = currentStmt + ":" + temp;
        if (stmtUseMap.containsKey(key)) {
            String versionedTemp = stmtUseMap.get(key);
            if (temp.equals("TEMP 7")) {
                if(DEBUG) System.err.println("DEBUG getReg: stmtUseMap[" + key + "] = " + versionedTemp);
            }
            if (info.getRegister(versionedTemp) != null) {
                if (info.isSpilled(versionedTemp)) {
                    return null;
                }
                String reg = info.getRegister(versionedTemp);
                if (DEBUG && temp.equals("TEMP 32")) {
                    System.err.println("  Returning register: " + reg);
                }
                return reg;
            }
        }
        
        // Try exact match (for parameters with no version)
        if(DEBUG) System.err.println("getReg(" + temp + "): trying exact match");
        if (info.getRegister(temp) != null) {
            if (info.isSpilled(temp)) {
                return null;
            }
            return info.getRegister(temp);
        }
        
        // Try to find with any suffix (last resort) - use the highest version number
        if(DEBUG) System.err.println("getReg(" + temp + "): trying any suffix");
        if(DEBUG) System.err.println("  All allocated temps in " + currentProcedure + ": " + info.getAllTemps());
        int maxVersion = -1;
        String bestTemp = null;
        String bestReg = null;
        for (String allocatedTemp : info.getAllTemps()) {
            if (allocatedTemp.startsWith(temp + "#")) {
                // Extract version number
                int hashIndex = allocatedTemp.lastIndexOf('#');
                if (hashIndex >= 0) {
                    try {
                        int version = Integer.parseInt(allocatedTemp.substring(hashIndex + 1));
                        if (version > maxVersion) {
                            if (!info.isSpilled(allocatedTemp)) {
                                String reg = info.getRegister(allocatedTemp);
                                if (reg != null) {
                                    maxVersion = version;
                                    bestTemp = allocatedTemp;
                                    bestReg = reg;
                                }
                            }
                        }
                    } catch (NumberFormatException e) {
                        // Skip if not a number
                    }
                }
            }
        }
        if (bestReg != null) {
            if(DEBUG) System.err.println("  -> found highest version: " + bestTemp + " in " + bestReg);
            return bestReg;
        }
        
        if(DEBUG) System.err.println("getReg(" + temp + "): NOT FOUND");
        return null; // Not found
    }
    
    private String getRegForDef(String temp) {
        RegisterAllocator.AllocationInfo info = allocationInfo.get(currentProcedure);
        
        // For definitions: try with current statement number suffix first
        String versionedTemp = temp + "#" + currentStmt;
        if (info.getRegister(versionedTemp) != null) {
            if (info.isSpilled(versionedTemp)) {
                return null;
            }
            return info.getRegister(versionedTemp);
        }
        
        // Try exact match without suffix
        if (info.getRegister(temp) != null) {
            if (info.isSpilled(temp)) {
                return null;
            }
            return info.getRegister(temp);
        }
        
        // Try to find with any suffix (last resort)
        for (String allocatedTemp : info.getAllTemps()) {
            if (allocatedTemp.startsWith(temp + "#")) {
                if (info.isSpilled(allocatedTemp)) {
                    return null;
                }
                return info.getRegister(allocatedTemp);
            }
        }
        
        return null; // Not found
    }
    
    private String loadTemp(String temp, String targetReg) {
        RegisterAllocator.AllocationInfo info = allocationInfo.get(currentProcedure);
        
        // For uses: lookup the versioned name from statement use map
        String key = currentStmt + ":" + temp;
        if (stmtUseMap.containsKey(key)) {
            String versionedTemp = stmtUseMap.get(key);
            if (info.isSpilled(versionedTemp)) {
                int slot = info.getStackSlot(versionedTemp);
                int stackParams = procedureStackParams.getOrDefault(currentProcedure, 0);
                int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
                int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
                return "ALOAD " + targetReg + " SPILLEDARG " + (stackParams + savedRegs + callerSavedRegs + slot) + "\n";
            }
        }
        
        // Fallback: check mostRecentDef (for RETURN and other post-statement uses)
        if (mostRecentDef.containsKey(temp)) {
            int defStmt = mostRecentDef.get(temp);
            String versionedTemp = temp + "#" + defStmt;
            if (info.isSpilled(versionedTemp)) {
                int slot = info.getStackSlot(versionedTemp);
                int stackParams = procedureStackParams.getOrDefault(currentProcedure, 0);
                int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
                int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
                return "ALOAD " + targetReg + " SPILLEDARG " + (stackParams + savedRegs + callerSavedRegs + slot) + "\n";
            }
        }
        
        // Try with current statement number suffix
        String versionedTemp = temp + "#" + currentStmt;
        if (info.isSpilled(versionedTemp)) {
            int slot = info.getStackSlot(versionedTemp);
            int stackParams = procedureStackParams.getOrDefault(currentProcedure, 0);
            int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
            int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
            return "ALOAD " + targetReg + " SPILLEDARG " + (stackParams + savedRegs + callerSavedRegs + slot) + "\n";
        }
        
        // Try exact match without suffix
        if (info.isSpilled(temp)) {
            int slot = info.getStackSlot(temp);
            int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
            int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
            return "ALOAD " + targetReg + " SPILLEDARG " + (savedRegs + callerSavedRegs + slot) + "\n";
        }
        
        // Try to find with any suffix (last resort)
        for (String allocatedTemp : info.getAllTemps()) {
            if (allocatedTemp.startsWith(temp + "#") && info.isSpilled(allocatedTemp)) {
                int slot = info.getStackSlot(allocatedTemp);
                int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
                int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
                return "ALOAD " + targetReg + " SPILLEDARG " + (savedRegs + callerSavedRegs + slot) + "\n";
            }
        }
        
        return "";
    }
    
    private String storeTempForDef(String temp, String sourceReg) {
        RegisterAllocator.AllocationInfo info = allocationInfo.get(currentProcedure);
        
        // First try with current statement number suffix (for definitions)
        String versionedTemp = temp + "#" + currentStmt;
        if (info.isSpilled(versionedTemp)) {
            int slot = info.getStackSlot(versionedTemp);
            int stackParams = procedureStackParams.getOrDefault(currentProcedure, 0);
            int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
            int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
            return "ASTORE SPILLEDARG " + (stackParams + savedRegs + callerSavedRegs + slot) + " " + sourceReg + "\n";
        }
        
        // Try exact match without suffix
        if (info.isSpilled(temp)) {
            int slot = info.getStackSlot(temp);
            int stackParams = procedureStackParams.getOrDefault(currentProcedure, 0);
            int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
            int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
            return "ASTORE SPILLEDARG " + (stackParams + savedRegs + callerSavedRegs + slot) + " " + sourceReg + "\n";
        }
        
        // Try to find with any suffix (last resort)
        for (String allocatedTemp : info.getAllTemps()) {
            if (allocatedTemp.startsWith(temp + "#") && info.isSpilled(allocatedTemp)) {
                int slot = info.getStackSlot(allocatedTemp);
                int savedRegs = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
                int callerSavedRegs = procedureCallerSavedRegsCount.getOrDefault(currentProcedure, 0);
                return "ASTORE SPILLEDARG " + (savedRegs + callerSavedRegs + slot) + " " + sourceReg + "\n";
            }
        }
        
        return "";
    }
    
    @Override
    public String visit(Goal n) {
        currentProcedure = "MAIN";
        
        // Calculate max args for MAIN
        currentMaxArgs = 0;
        calculateMaxArgs(n.f1);
        procedureMaxArgs.put("MAIN", currentMaxArgs);
        
        // MAIN header
        RegisterAllocator.AllocationInfo mainInfo = allocationInfo.get("MAIN");
        int spilledSlots = mainInfo.getNumStackSlots();
        
        // Count how many s-registers and t-registers are actually used in MAIN
        // Determine which registers are actually allocated
        Set<String> allocatedRegisters = new HashSet<>();
        for (String temp : mainInfo.getAllTemps()) {
            if (!mainInfo.isSpilled(temp)) {
                String reg = mainInfo.getRegister(temp);
                if (reg != null) {
                    allocatedRegisters.add(reg);
                }
            }
        }
        
        List<String> usedSRegs = new ArrayList<>();
        List<String> usedTRegs = new ArrayList<>();
        
        // Only include s-registers that are actually allocated
        for (int i = 0; i < 8; i++) {
            String reg = "s" + i;
            if (allocatedRegisters.contains(reg)) {
                usedSRegs.add(reg);
            }
        }
        
        // Only include t-registers that are actually allocated
        for (int i = 0; i < 10; i++) {
            String reg = "t" + i;
            if (allocatedRegisters.contains(reg)) {
                usedTRegs.add(reg);
            }
        }
        
        // But for saving around calls, we need to save ALL t-registers (t0-t9)
        // because they are caller-saved
        List<String> allTRegs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            allTRegs.add("t" + i);
        }
        
        // MAIN is special: it doesn't need to save s-registers because it's never called
        // So MAIN only needs stack space for t-registers (caller-saved around calls)
        int savedRegsCount = 0; // Don't save s-registers in MAIN
        // For t-registers: reserve space for ALL 10 t-registers because they are caller-saved
        // and need to be saved around CALL statements
        int callerSavedRegsCount = 10; // Always reserve 10 slots for t0-t9 (caller-saved)
        int totalStackSlots = callerSavedRegsCount + spilledSlots;  // No s-register saving for MAIN
        int maxArgs = procedureMaxArgs.get("MAIN");
        
        // Store saved registers counts and lists for MAIN
        procedureSavedRegsCount.put("MAIN", savedRegsCount);
        procedureCallerSavedRegsCount.put("MAIN", callerSavedRegsCount);
        procedureUsedSRegs.put("MAIN", new ArrayList<>()); // Empty - don't save s-regs in MAIN
        procedureUsedTRegs.put("MAIN", allTRegs); // Save all 10 t-registers around calls
        
        output.append("MAIN [0] [").append(totalStackSlots).append("] [").append(maxArgs).append("]\n");
        
        // Don't save s-registers at the beginning of MAIN (it's never called by anyone)
        
        // Generate MAIN body
        n.f1.accept(this);
        
        // Don't restore s-registers at the end of MAIN (we didn't save them)
        
        output.append("END\n");
        
        // Spill info
        if (mainInfo.hasSpills()) {
            output.append("// SPILLED\n");
        } else {
            output.append("// NOTSPILLED\n");
        }
        
        // Process procedures
        if (n.f3.present()) {
            for (Node procNode : n.f3.nodes) {
                procNode.accept(this);
            }
        }
        
        return output.toString();
    }
    
    @Override
    public String visit(Procedure n) {
        String label = n.f0.f0.tokenImage;
        currentProcedure = label;
        
        // Calculate max args
        currentMaxArgs = 0;
        calculateMaxArgs(n.f4.f1);
        procedureMaxArgs.put(label, currentMaxArgs);
        
        int numParams = Integer.parseInt(n.f2.f0.tokenImage);
        RegisterAllocator.AllocationInfo procInfo = allocationInfo.get(label);
        
        if(DEBUG) System.err.println("=== Register assignments for " + label + " ===");
        for (String temp : procInfo.getAllTemps()) {
            if (!procInfo.isSpilled(temp)) {
                if(DEBUG) System.err.println("  " + temp + " -> " + procInfo.getRegister(temp));
            }
        }
        if(DEBUG) System.err.println("===========================================");
        
        // Calculate stack slots needed
        int spilledSlots = procInfo.getNumStackSlots();
        
        // Calculate how many parameters are passed on the stack (beyond first 4)
        int stackParams = Math.max(0, numParams - 4);
        
        // Count how many s-registers and t-registers are actually used
        // Determine which registers are actually allocated
        Set<String> allocatedRegisters = new HashSet<>();
        for (String temp : procInfo.getAllTemps()) {
            if (!procInfo.isSpilled(temp)) {
                String reg = procInfo.getRegister(temp);
                if (reg != null) {
                    allocatedRegisters.add(reg);
                }
            }
        }
        
        List<String> usedSRegs = new ArrayList<>();
        List<String> usedTRegs = new ArrayList<>();
        
        // Only include s-registers that are actually allocated
        for (int i = 0; i < 8; i++) {
            String reg = "s" + i;
            if (allocatedRegisters.contains(reg)) {
                usedSRegs.add(reg);
            }
        }
        
        // Only include t-registers that are actually allocated
        for (int i = 0; i < 10; i++) {
            String reg = "t" + i;
            if (allocatedRegisters.contains(reg)) {
                usedTRegs.add(reg);
            }
        }
        
        // But for saving around calls, we need to save ALL t-registers (t0-t9)
        // because they are caller-saved
        List<String> allTRegs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            allTRegs.add("t" + i);
        }
        
        int savedRegsCount = usedSRegs.size();
        // For t-registers: reserve space for ALL 10 t-registers because they are caller-saved
        int callerSavedRegsCount = 10; // Always reserve 10 slots for t0-t9 (caller-saved)
        
        // Total stack slots = incoming stack params + s-registers we use + t-registers we use + spilled temps
        int totalStackSlots = stackParams + savedRegsCount + callerSavedRegsCount + spilledSlots;
        int maxArgs = procedureMaxArgs.get(label);
        
        // Store saved registers counts and lists for this procedure
        procedureSavedRegsCount.put(label, savedRegsCount);
        procedureCallerSavedRegsCount.put(label, callerSavedRegsCount);
        procedureStackParams.put(label, stackParams);
        procedureUsedSRegs.put(label, usedSRegs);
        procedureUsedTRegs.put(label, allTRegs); // Save all 10 t-registers around calls
        
        output.append(label).append(" [").append(numParams).append("] [").append(totalStackSlots);
        output.append("] [").append(maxArgs).append("]\n");
        
        // Save only the s-registers we actually use (after stack params)
        int stackOffset = stackParams;
        for (String reg : usedSRegs) {
            output.append("ASTORE SPILLEDARG ").append(stackOffset).append(" ").append(reg).append("\n");
            stackOffset++;
        }
        
        // Move parameters from a-registers to their allocated registers
        // TEMP 0 to TEMP (numParams-1) are the parameters
        for (int i = 0; i < numParams && i < 4; i++) {
            String tempName = "TEMP " + i;
            String destReg = procInfo.getRegister(tempName);
            String aReg = "a" + i;
            
            if (destReg != null && !destReg.equals(aReg)) {
                output.append("MOVE ").append(destReg).append(" ").append(aReg).append("\n");
            } else if (destReg == null && procInfo.isSpilled(tempName)) {
                // Parameter is spilled, store it (after stackParams + s-regs + t-regs)
                Integer slot = procInfo.getStackSlot(tempName);
                if (slot != null) {
                    output.append("ASTORE SPILLEDARG ").append(stackParams + savedRegsCount + callerSavedRegsCount + slot).append(" ").append(aReg).append("\n");
                }
            }
        }
        
        // Handle parameters passed on stack (beyond first 4)
        for (int i = 4; i < numParams; i++) {
            String tempName = "TEMP " + i;
            String destReg = procInfo.getRegister(tempName);
            
            // Load from incoming stack position to register or spilled location
            if (destReg != null) {
                output.append("ALOAD ").append(destReg).append(" SPILLEDARG ").append(i - 4).append("\n");
            } else if (procInfo.isSpilled(tempName)) {
                // Move from incoming stack to our stack (after stackParams + s-regs + t-regs)
                Integer slot = procInfo.getStackSlot(tempName);
                if (slot != null) {
                    output.append("ALOAD v0 SPILLEDARG ").append(i - 4).append("\n");
                    output.append("ASTORE SPILLEDARG ").append(stackParams + savedRegsCount + callerSavedRegsCount + slot).append(" v0\n");
                }
            }
        }
        
        // Generate procedure body
        n.f4.f1.accept(this);
        
        // Handle return value  
        // The return expression uses the version that's live at exit
        // Use liveOut of the last statement to find the correct version
        LivenessAnalyzer.ProcedureLivenessInfo procLiveness = livenessInfo.get(label);
        if (procLiveness != null && !procLiveness.getStatements().isEmpty()) {
            int lastStmtIndex = procLiveness.getStatements().size() - 1;
            Set<String> liveAtExit = procLiveness.getLiveOut(lastStmtIndex);
            if(DEBUG) System.err.println("Procedure " + label + ": liveOut at exit = " + liveAtExit);
        }
        
        String returnExp = n.f4.f3.accept(this);
        if(DEBUG) System.err.println("Procedure " + label + " return: returnExp = '" + returnExp + "', currentStmt = " + currentStmt);
        
        // Get liveness info for this statement
        LivenessAnalyzer.ProcedureLivenessInfo thisLivenessInfo = livenessInfo.get(label);
        if (thisLivenessInfo != null) {
            if(DEBUG) System.err.println("  liveOut[" + currentStmt + "] = " + thisLivenessInfo.getLiveOut(currentStmt));
        }
        
        // DEBUGGING: Check what stmtUseMap says for RETURN's temp
        if (returnExp != null && returnExp.equals("v0") && n.f4.f3.f0.which == 0) {
            Temp returnTemp = (Temp) n.f4.f3.f0.choice;
            String returnTempName = getTempName(returnTemp);
            String stmtUseKey = currentStmt + ":" + returnTempName;
            if (stmtUseMap.containsKey(stmtUseKey)) {
                String versionedTemp = stmtUseMap.get(stmtUseKey);
                if(DEBUG) System.err.println("  stmtUseMap[" + stmtUseKey + "] = " + versionedTemp);
                if (!procInfo.isSpilled(versionedTemp)) {
                    String reg = procInfo.getRegister(versionedTemp);
                    if(DEBUG) System.err.println("  That temp is in register: " + reg);
                    if (reg != null) {
                        returnExp = reg;
                    }
                }
            } else {
                if(DEBUG) System.err.println("  stmtUseMap does NOT contain key: " + stmtUseKey);
                // Check liveOut of PREVIOUS statement for the versioned temp
                if (thisLivenessInfo != null) {
                    Set<String> liveOut = thisLivenessInfo.getLiveOut(currentStmt - 1);
                    if(DEBUG) System.err.println("  Checking liveOut[" + (currentStmt - 1) + "] temps...");
                    for (String liveTemp : liveOut) {
                        if(DEBUG) System.err.println("    liveOut contains: " + liveTemp);
                        if (liveTemp.startsWith(returnTempName + "#") || liveTemp.equals(returnTempName)) {
                            if(DEBUG) System.err.println("      This matches our return temp!");
                            if (!procInfo.isSpilled(liveTemp)) {
                                String reg = procInfo.getRegister(liveTemp);
                                if(DEBUG) System.err.println("      It's in register: " + reg);
                                if (reg != null) {
                                    returnExp = reg;
                                    break;
                                }
                            }
                        }
                    }
                }
                // If still not found, search all allocated temps for any version of this temp
                if (returnExp.equals("v0")) {
                    if(DEBUG) System.err.println("  Still not found in liveOut or allocations");
                    if(DEBUG) System.err.println("  Searching stmtUseMap for ANY statement that uses " + returnTempName);
                    // Search ALL stmtUseMap entries to find any statement that uses this temp
                    String foundVersionedTemp = null;
                    for (Map.Entry<String, String> entry : stmtUseMap.entrySet()) {
                        if (entry.getKey().endsWith(":" + returnTempName)) {
                            foundVersionedTemp = entry.getValue();
                            if(DEBUG) System.err.println("    Found stmtUseMap[" + entry.getKey() + "] = " + foundVersionedTemp);
                            break; // Use the first one we find (could be improved to find latest)
                        }
                    }
                    if (foundVersionedTemp != null && !procInfo.isSpilled(foundVersionedTemp)) {
                        String reg = procInfo.getRegister(foundVersionedTemp);
                        if(DEBUG) System.err.println("    That versioned temp is in register: " + reg);
                        if (reg != null) {
                            returnExp = reg;
                        }
                    }
                }
            }
        }
        
        if (returnExp != null && !returnExp.equals("v0")) {
            output.append("MOVE v0 ").append(returnExp).append("\n");
        } else if (returnExp != null && returnExp.equals("v0")) {
            // Return expression returned v0, but it might be because temp wasn't found
            // Try to find the return temp directly from microIR
            // The return value is in n.f4.f3 (SimpleExp)
            if(DEBUG) System.err.println("  Return exp is v0, checking if it's a Temp...");
            if(DEBUG) System.err.println("  n.f4.f3.f0.which = " + n.f4.f3.f0.which);
            if (n.f4.f3.f0.which == 0) { // It's a Temp
                if(DEBUG) System.err.println("  It's a Temp!");
                Temp returnTemp = (Temp) n.f4.f3.f0.choice;
                String returnTempName = getTempName(returnTemp);
                if(DEBUG) System.err.println("  returnTempName = " + returnTempName);
                // The register allocator may have renamed/coalesced this temp
                // Look through all temps to find ones that start with the same name (without version)
                // and use the one with the highest version
                String foundReg = null;
                int maxVersion = -1;
                if(DEBUG) System.err.println("  Searching through all allocated temps...");
                int count = 0;
                for (String allocatedTemp : procInfo.getAllTemps()) {
                    count++;
                    if (count < 5) {
                        if(DEBUG) System.err.println("    Checking: '" + allocatedTemp + "' starts with '" + returnTempName + "#" + "'?");
                    }
                    // Extract base temp name (before #)
                    String baseName = allocatedTemp.contains("#") ? 
                        allocatedTemp.substring(0, allocatedTemp.indexOf('#')) : allocatedTemp;
                    if (baseName.equals(returnTempName)) {
                        if(DEBUG) System.err.println("    Found potential match: " + allocatedTemp);
                        if (allocatedTemp.contains("#")) {
                            int hashIndex = allocatedTemp.lastIndexOf('#');
                            try {
                                int version = Integer.parseInt(allocatedTemp.substring(hashIndex + 1));
                                if (version > maxVersion && !procInfo.isSpilled(allocatedTemp)) {
                                    String reg = procInfo.getRegister(allocatedTemp);
                                    if(DEBUG) System.err.println("      Version " + version + " is in register " + reg);
                                    if (reg != null) {
                                        maxVersion = version;
                                        foundReg = reg;
                                    }
                                }
                            } catch (NumberFormatException e) {
                                // Skip
                            }
                        }
                    }
                }
                if (foundReg != null && !foundReg.equals("v0")) {
                    if(DEBUG) System.err.println("  Found return value in " + foundReg + ", generating MOVE");
                    output.append("MOVE v0 ").append(foundReg).append("\n");
                } else {
                    if(DEBUG) System.err.println("  No valid return register found (foundReg = " + foundReg + ")");
                }
            }
        }
        
        // Restore only the s-registers we actually use (account for stackParams offset)
        stackOffset = stackParams;
        for (String reg : usedSRegs) {
            output.append("ALOAD ").append(reg).append(" SPILLEDARG ").append(stackOffset).append("\n");
            stackOffset++;
        }
        
        output.append("END\n");
        
        // Spill info
        if (procInfo.hasSpills()) {
            output.append("// SPILLED\n");
        } else {
            output.append("// NOTSPILLED\n");
        }
        
        return null;
    }
    
    @Override
    public String visit(StmtList n) {
        if (n.f0.present()) {
            currentStmt = 0; // Reset statement counter for this procedure
            mostRecentDef.clear(); // Reset definition tracking
            
            // Build use map from liveness info
            LivenessAnalyzer.ProcedureLivenessInfo procInfo = livenessInfo.get(currentProcedure);
            if (procInfo != null) {
                for (LivenessAnalyzer.ProcedureLivenessInfo.StatementInfo stmt : procInfo.getStatements()) {
                    for (String use : stmt.getUse()) {
                        // Store mapping from base name to versioned name for this statement
                        if (use.contains("#")) {
                            String baseName = use.substring(0, use.lastIndexOf('#'));
                            String key = stmt.index + ":" + baseName;
                            stmtUseMap.put(key, use);
                        } else {
                            // No version (parameter)
                            stmtUseMap.put(stmt.index + ":" + use, use);
                        }
                    }
                }
            }
            
            for (Node seqNode : n.f0.nodes) {
                NodeSequence seq = (NodeSequence) seqNode;
                // seq has (Label)? Stmt()
                Node labelOptNode = seq.elementAt(0);
                Node stmtNode = seq.elementAt(1);
                
                // Process optional label
                NodeOptional labelOpt = (NodeOptional) labelOptNode;
                if (labelOpt.present()) {
                    Label label = (Label) labelOpt.node;
                    output.append(label.f0.tokenImage).append("\n");
                }
                
                // Process statement
                Stmt stmt = (Stmt) stmtNode;
                stmt.accept(this);
                currentStmt++; // Increment after processing each statement
            }
        }
        return null;
    }
    
    @Override
    public String visit(Stmt n) {
        n.f0.accept(this);
        return null;
    }
    
    @Override
    public String visit(NoOpStmt n) {
        output.append("NOOP\n");
        return null;
    }
    
    @Override
    public String visit(ErrorStmt n) {
        output.append("ERROR\n");
        return null;
    }
    
    @Override
    public String visit(CJumpStmt n) {
        String tempName = getTempName(n.f1);
        String reg = getReg(tempName);
        String label = n.f2.f0.tokenImage;
        
        if (reg == null) {
            // Spilled - load into v0
            output.append(loadTemp(tempName, "v0"));
            output.append("CJUMP v0 ").append(label).append("\n");
        } else {
            output.append("CJUMP ").append(reg).append(" ").append(label).append("\n");
        }
        return null;
    }
    
    @Override
    public String visit(JumpStmt n) {
        String label = n.f1.f0.tokenImage;
        output.append("JUMP ").append(label).append("\n");
        return null;
    }
    
    @Override
    public String visit(HStoreStmt n) {
        String temp1Name = getTempName(n.f1);
        String temp2Name = getTempName(n.f3);
        String offset = n.f2.f0.tokenImage;
        
        String reg1 = getReg(temp1Name);
        String reg2 = getReg(temp2Name);
        
        if (reg1 == null) {
            output.append(loadTemp(temp1Name, "v0"));
            reg1 = "v0";
        }
        if (reg2 == null) {
            output.append(loadTemp(temp2Name, "v1"));
            reg2 = "v1";
        }
        
        output.append("HSTORE ").append(reg1).append(" ").append(offset).append(" ").append(reg2).append("\n");
        return null;
    }
    
    @Override
    public String visit(HLoadStmt n) {
        String destTempName = getTempName(n.f1);
        String srcTempName = getTempName(n.f2);
        String offset = n.f3.f0.tokenImage;
        
        if(DEBUG) System.err.println("\n=== HLOAD at stmt " + currentStmt + " in " + currentProcedure + " ===");
        if(DEBUG) System.err.println("  MicroIR: HLOAD " + destTempName + " " + srcTempName + " " + offset);
        
        String destReg = getRegForDef(destTempName);  // Use getRegForDef for destination
        String srcReg = getReg(srcTempName);  // Use getReg for source (use)
        
        if(DEBUG) System.err.println("  destReg = " + destReg + ", srcReg = " + srcReg);
        
        if (srcReg == null) {
            if(DEBUG) System.err.println("  Source temp " + srcTempName + " needs to be loaded from spill");
            output.append(loadTemp(srcTempName, "v0"));
            srcReg = "v0";
        }
        
        if (destReg == null) {
            // Load into v1, then store
            if(DEBUG) System.err.println("  Dest temp " + destTempName + " is spilled");
            output.append("HLOAD v1 ").append(srcReg).append(" ").append(offset).append("\n");
            output.append(storeTempForDef(destTempName, "v1"));
        } else {
            if(DEBUG) System.err.println("  Generated: HLOAD " + destReg + " " + srcReg + " " + offset);
            output.append("HLOAD ").append(destReg).append(" ").append(srcReg).append(" ").append(offset).append("\n");
        }
        
        // Record this definition
        mostRecentDef.put(destTempName, currentStmt);
        
        return null;
    }
    
    @Override
    public String visit(MoveStmt n) {
        String destTempName = getTempName(n.f1);
        String destReg = getRegForDef(destTempName);  // Use getRegForDef for destination
        
        String expResult = n.f2.accept(this);
        
        if (destReg == null) {
            // Spilled - store from v0/v1
            if (expResult == null || expResult.isEmpty()) {
                // Result already in output, value in v0
                output.append(storeTempForDef(destTempName, "v0"));
            } else {
                output.append("MOVE v0 ").append(expResult).append("\n");
                output.append(storeTempForDef(destTempName, "v0"));
            }
        } else {
            if (expResult == null || expResult.isEmpty()) {
                // Complex expression already generated, result in v0
                output.append("MOVE ").append(destReg).append(" v0\n");
            } else {
                output.append("MOVE ").append(destReg).append(" ").append(expResult).append("\n");
            }
        }
        
        // Record this definition
        mostRecentDef.put(destTempName, currentStmt);
        
        return null;
    }
    
    @Override
    public String visit(PrintStmt n) {
        String exp = n.f1.accept(this);
        output.append("PRINT ").append(exp).append("\n");
        return null;
    }
    
    @Override
    public String visit(Exp n) {
        return n.f0.accept(this);
    }
    
    @Override
    public String visit(Call n) {
        // Count args
        int numArgs = 0;
        if (n.f3.present()) {
            numArgs = n.f3.size();
        }
        currentMaxArgs = Math.max(currentMaxArgs, numArgs);
        
        // Save only the t-registers we actually use
        int stackParams = procedureStackParams.getOrDefault(currentProcedure, 0);
        int savedRegsCount = procedureSavedRegsCount.getOrDefault(currentProcedure, 0);
        List<String> usedTRegs = procedureUsedTRegs.getOrDefault(currentProcedure, new ArrayList<>());
        int stackOffset = stackParams + savedRegsCount;
        
        for (String reg : usedTRegs) {
            output.append("ASTORE SPILLEDARG ").append(stackOffset).append(" ").append(reg).append("\n");
            stackOffset++;
        }
        
        // Get the call target (function pointer) first and preserve it
        String target = n.f1.accept(this);
        
        // If target was loaded into v0 (spilled), move it to t0 to preserve it
        // t0 is now saved on stack so we can use it temporarily
        if (target.equals("v0")) {
            output.append("MOVE t0 v0\n");
            target = "t0";
        }
        
        // Move first 4 args to a0-a3
        int argIndex = 0;
        if (n.f3.present()) {
            for (Node tempNode : n.f3.nodes) {
                String tempName = getTempName((Temp) tempNode);
                String reg = getReg(tempName);
                
                if (argIndex < 4) {
                    String aReg = "a" + argIndex;
                    if (reg == null) {
                        output.append(loadTemp(tempName, "v0"));
                        output.append("MOVE ").append(aReg).append(" v0\n");
                    } else {
                        output.append("MOVE ").append(aReg).append(" ").append(reg).append("\n");
                    }
                } else {
                    // PASSARG for args beyond 4
                    if (reg == null) {
                        output.append(loadTemp(tempName, "v0"));
                        output.append("PASSARG ").append(argIndex - 3).append(" v0\n");
                    } else {
                        output.append("PASSARG ").append(argIndex - 3).append(" ").append(reg).append("\n");
                    }
                }
                argIndex++;
            }
        }
        
        // Call
        output.append("CALL ").append(target).append("\n");
        
        // Restore only the t-registers we actually use
        stackOffset = stackParams + savedRegsCount;
        for (String reg : usedTRegs) {
            output.append("ALOAD ").append(reg).append(" SPILLEDARG ").append(stackOffset).append("\n");
            stackOffset++;
        }
        
        return ""; // Result in v0
    }
    
    @Override
    public String visit(HAllocate n) {
        String size = n.f1.accept(this);
        output.append("MOVE v0 HALLOCATE ").append(size).append("\n");
        return "";
    }
    
    @Override
    public String visit(BinOp n) {
        String op = n.f0.accept(this);
        String tempName = getTempName(n.f1);
        String reg = getReg(tempName);
        
        // First, get the second operand (might load into v0)
        String simpleExp = n.f2.accept(this);
        
        // Now handle the first operand
        if (reg == null) {
            // If both are spilled, we need to use different registers
            // simpleExp might have loaded into v0, so load first operand into v1
            output.append(loadTemp(tempName, "v1"));
            output.append("MOVE v0 ").append(op).append(" v1 ").append(simpleExp).append("\n");
        } else {
            output.append("MOVE v0 ").append(op).append(" ").append(reg).append(" ").append(simpleExp).append("\n");
        }
        
        return "";
    }
    
    @Override
    public String visit(Operator n) {
        NodeToken token = (NodeToken) n.f0.choice;
        return token.tokenImage;
    }
    
    @Override
    public String visit(SimpleExp n) {
        if (n.f0.which == 0) { // Temp
            Temp temp = (Temp) n.f0.choice;
            String tempName = getTempName(temp);
            if(DEBUG) System.err.println("SimpleExp visiting temp: " + tempName);
            String reg = getReg(tempName);
            if(DEBUG) System.err.println("  getReg returned: " + reg);
            
            if (reg == null) {
                // Spilled - need to load
                if(DEBUG) System.err.println("  Calling loadTemp");
                output.append(loadTemp(tempName, "v0"));
                return "v0";
            }
            return reg;
        } else if (n.f0.which == 1) { // IntegerLiteral
            IntegerLiteral lit = (IntegerLiteral) n.f0.choice;
            return lit.f0.tokenImage;
        } else { // Label
            Label label = (Label) n.f0.choice;
            return label.f0.tokenImage;
        }
    }
    
    @Override
    public String visit(Temp n) {
        String tempName = getTempName(n);
        String reg = getReg(tempName);
        
        if (reg == null) {
            // This is for use in SimpleExp context
            output.append(loadTemp(tempName, "v0"));
            return "v0";
        }
        
        return reg;
    }
    
    @Override
    public String visit(IntegerLiteral n) {
        return n.f0.tokenImage;
    }
    
    private String getTempName(Temp temp) {
        return "TEMP " + temp.f1.f0.tokenImage;
    }
    
    private void calculateMaxArgs(StmtList stmtList) {
        if (stmtList.f0.present()) {
            for (Node seqNode : stmtList.f0.nodes) {
                NodeSequence seq = (NodeSequence) seqNode;
                Node stmtNode = seq.elementAt(1);
                Stmt stmt = (Stmt) stmtNode;
                
                if (stmt.f0.which == 6) { // MoveStmt
                    MoveStmt move = (MoveStmt) stmt.f0.choice;
                    countArgsInExp(move.f2);
                }
            }
        }
    }
    
    private void countArgsInExp(Exp exp) {
        if (exp.f0.which == 0) { // Call
            Call call = (Call) exp.f0.choice;
            if (call.f3.present()) {
                currentMaxArgs = Math.max(currentMaxArgs, call.f3.size());
            }
        }
    }
}
