import syntaxtree.*;
import visitor.*;

public class P4 {
   public static void main(String [] args) {
      try {
         Node root = new MiniIRParser(System.in).Goal();
         GJDepthFirst<String,Void> v = new GJDepthFirst<String,Void>();
         root.accept(v,null);
         System.out.println(v.code.toString());
      }
      catch (ParseException e) {
         System.out.println(e.toString());
      }
   }
} 


