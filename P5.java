import java.util.Map;
import syntaxtree.*;
import visitor.*;

public class P5 {
   public static void main(String [] args) {
      try {
         Node root = new microIRParser(System.in).Goal();
         
         // Pass 1: Liveness Analysis
         LivenessAnalyzer livenessAnalyzer = new LivenessAnalyzer();
         root.accept(livenessAnalyzer);
         Map<String, LivenessAnalyzer.ProcedureLivenessInfo> livenessInfo = 
             livenessAnalyzer.getProcedureLiveness();
         
         // Pass 2: Register Allocation
         RegisterAllocator registerAllocator = new RegisterAllocator();
         registerAllocator.allocateRegisters(livenessInfo);
         Map<String, RegisterAllocator.AllocationInfo> allocationInfo = 
             registerAllocator.getProcedureAllocation();
         
         // Pass 3: Code Generation
         MiniRACodeGenerator codeGenerator = new MiniRACodeGenerator(livenessInfo, allocationInfo);
         root.accept(codeGenerator);
         String miniRACode = codeGenerator.getGeneratedCode();
         
         // Output the generated miniRA code
         System.out.print(miniRACode);
      }
      catch (ParseException e) {
         System.out.println(e.toString());
      }
   }
} 


