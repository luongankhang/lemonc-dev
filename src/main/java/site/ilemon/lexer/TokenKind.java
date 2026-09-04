package site.ilemon.lexer;

public enum TokenKind {

		/*** End of file token ****/
        EOF,

		/*** Keywords ****/
		Class,
		Main,
		Void,
		String,
		Int,
		Float,
		Double,
		Bool,
		Byte,
		Short,
		Char,
		Long,
		While,
		For,
		True,
		False,
		If,
		Else,
		Printf,
		Return,
		Break,
		Continue,

		/*** Arithmetic operators ****/
		Add,
		Sub,
		Mul,
		Div,
		Mod,

		/*** Comparison operators ****/
		LT,			// <
		GT,			// >
		LTE,		// <=
		GTE,		// >=
		EQ,			// ==
		NEQ,		// !=

		/*** Logical operators ****/
		And,		// &&
		Or,			// ||
		Not,		// !

		/*** Delimiters ****/
		DoubleQuotation,	// "
		Lbrace,				// {
		Rbrace,				// }
		Lparen,				// (
		Rparen,				// )
		Lbracket,			// [
		Rbracket,			// ]
		Semicolon,			// ;
		Comma,				// ,
		Dot,				// .

		Id,
		Num,
		FloatLiteral,
		CharLiteral,
		Assign,
		Unknown, PrintLine,

	}
