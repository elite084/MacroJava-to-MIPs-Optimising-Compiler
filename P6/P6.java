import syntaxtree.*;

public class P6 {
   public static void main(String [] args) {
      try {
         Node root = new MiniRAParser(System.in).Goal();
         // First collect per-procedure info
         visitor.ProcInfoCollector collector = new visitor.ProcInfoCollector();
         root.accept(collector);

         visitor.CodeGenVisitor gen = new visitor.CodeGenVisitor(collector.getProcMap());
         root.accept(gen);
      }
      catch (ParseException e) {
         System.err.println(e.toString());
      }
   }
}


