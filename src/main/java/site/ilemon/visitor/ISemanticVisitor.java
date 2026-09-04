package site.ilemon.visitor;


import site.ilemon.ast.Ast;

/**
 * Semantic analysis visitor interface.
 * @author andy
 */
public interface ISemanticVisitor {

	void visit(Ast.Expr.T obj);
	void visit(Ast.Expr.Add obj);
	void visit(Ast.Expr.And obj);
	void visit(Ast.Expr.Call obj);
	void visit(Ast.Expr obj);
	void visit(Ast.Expr.GT obj);
	void visit(Ast.Expr.LT obj);
	void visit(Ast.Expr.LTE obj);
	void visit(Ast.Expr.GTE obj);
	void visit(Ast.Expr.EQ obj);
	void visit(Ast.Expr.NEQ obj);
	void visit(Ast.Expr.Id obj);
	void visit(Ast.Expr.Div obj);
	void visit(Ast.Expr.Mod obj);
	void visit(Ast.Expr.Mul obj);
	void visit(Ast.Expr.Number obj);

	void visit(Ast.Expr.Sub obj);
	void visit(Ast.Expr.Or obj);
	void visit(Ast.Expr.True obj);
	void visit(Ast.Expr.False obj);
	void visit(Ast.Expr.Not obj);
	void visit(Ast.Expr.Str obj);
	void visit(Ast.Expr.ArrayAccess obj);
	void visit(Ast.Expr.ArrayLength obj);

	void visit(Ast.Type.T obj);
	void visit(Ast.Type.Bool obj);
	void visit(Ast.Type.Byte obj);
	void visit(Ast.Type.Short obj);
	void visit(Ast.Type.Long obj);
	void visit(Ast.Type.Float obj);
	void visit(Ast.Type.Double obj);
	void visit(Ast.Type.Str obj);
	void visit(Ast.Type obj);
	void visit(Ast.Type.Void obj);
	void visit(Ast.Type.Int obj);
	void visit(Ast.Type.IntArray obj);
	void visit(Ast.Type.ByteArray obj);
	void visit(Ast.Type.ShortArray obj);
	void visit(Ast.Type.LongArray obj);
	void visit(Ast.Type.FloatArray obj);
	void visit(Ast.Type.DoubleArray obj);
	void visit(Ast.Type.BoolArray obj);
	void visit(Ast.Type.StringArray obj);

	void visit(Ast.Program.T programSingle);
	void visit(Ast.Declare.T obj);
	void visit(Ast.MainClass.T obj);
	void visit(Ast.Method.MethodSingle obj);

	void visit(Ast.Stmt.If obj);
	void visit(Ast.Stmt.T obj);
	void visit(Ast.Stmt.Assign obj);
	void visit(Ast.Stmt.Block obj);
	void visit(Ast.Stmt.Printf obj);
	void visit(Ast.Stmt.PrintLine obj);
	void visit(Ast.Stmt.Return obj);
	void visit(Ast.Stmt.While obj);
	void visit(Ast.Stmt.For obj);
	void visit(Ast.Stmt.Break obj);
	void visit(Ast.Stmt.Continue obj);
	void visit(Ast.Stmt.Call obj);
	void visit(Ast.Stmt.ArrayAssign obj);

}
