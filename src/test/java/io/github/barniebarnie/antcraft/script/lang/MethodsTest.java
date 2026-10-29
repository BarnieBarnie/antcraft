package io.github.barniebarnie.antcraft.script.lang;

import static io.github.barniebarnie.antcraft.script.lang.Scripts.error;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.eval;
import static io.github.barniebarnie.antcraft.script.lang.Scripts.run;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The str, list and dict methods of docs/language/index.md, each checked against what CPython 3.14 gives. */
class MethodsTest {
	@Test
	void strSplit() {
		assertEquals("['a', 'b', 'c'] ['a', 'b'] ['a', 'b', '', 'c'] ['a', 'b,c'] ['a', 'b c'] [] ['']",
				eval("'a b  c'.split(), '  a b '.split(), 'a,b,,c'.split(','), 'a,b,c'.split(',', 1), "
						+ "'a b c'.split(None, 1), ''.split(), ''.split(',')"));
		assertEquals("['a', 'b c']", eval("'a b c'.split(maxsplit=1)"));
		assertEquals("empty separator", error("'abc'.split('')"));
		assertEquals("must be str or None, not int", error("'a'.split(5)"));
	}

	@Test
	void strJoin() {
		assertEquals("a,b||a,b,c", eval("','.join(['a', 'b']), ','.join([]), ','.join('abc')").replace(" ", "|"));
		assertEquals("sequence item 1: expected str instance, int found", error("','.join(['a', 1])"));
	}

	@Test
	void strStripStartswithEndswithReplaceLowerUpper() {
		assertEquals("hi hi hi", eval("'  hi \\n'.strip(), 'xxhixx'.strip('x'), 'hi'.strip(None)"));
		assertEquals("True True True False", eval("'hello'.startswith('he'), 'hello'.startswith(('x', 'h')), "
				+ "'hello'.endswith('lo'), 'hello'.endswith(('x', 'y'))"));
		assertEquals("startswith first arg must be str or a tuple of str, not int", error("'hello'.startswith(1)"));
		assertEquals("bbb bba -a-b-c-", eval("'aaa'.replace('a', 'b'), 'aaa'.replace('a', 'b', 2), "
				+ "'abc'.replace('', '-')"));
		assertEquals("abc ABC", eval("'AbC'.lower(), 'AbC'.upper()"));
	}

	@Test
	void strFormat() {
		assertEquals("1 and x|b a|bot!|   ab||'ab'|{} 3|3.14", run("""
				print('{} and {}'.format(1, 'x'), '{1} {0}'.format('a', 'b'), '{name}!'.format(name='bot'),
				      '{:>5}|'.format('ab'), '{0!r}'.format('ab'), '{{}} {}'.format(3), '{:.2f}'.format(3.14159), sep='|')
				"""));
		assertEquals("Replacement index 0 out of range for positional args tuple", error("'{}'.format()"));
		assertEquals("Replacement index 2 out of range for positional args tuple", error("'{2}'.format(1)"));
		assertEquals("key 'x' is not in the keyword arguments", error("'{x}'.format(y=1)"));
		assertEquals("cannot switch from automatic field numbering to manual field specification",
				error("'{} {0}'.format(1)"));
		assertEquals("Single '{' encountered in format string", error("'{'.format(1)"));
		assertEquals("Single '}' encountered in format string", error("'}'.format(1)"));
		assertEquals("Unknown format code 'd' for object of type 'str'", error("'{:d}'.format('x')"));
	}

	@Test
	void strFormatNestedSpecs() {
		assertEquals("ab   |3.14|   7|   2.500|  'a'  ", run("""
				print('{:{}}'.format('ab', 5), '{0:{1}}'.format(3.14159, '.2f'), '{:{}{}}'.format(7, '>', 4),
				      '{0:>{w}.{p}f}'.format(2.5, w=8, p=3), '{!r:^{}}'.format('a', 7), sep='|')
				"""));
		assertEquals("Max string recursion exceeded", error("'{:{:{}}}'.format(1, 2, 3)"));
		assertEquals("unmatched '{' in format spec", error("'{:{}'.format(1, 2)"));
		assertEquals("cannot switch from manual field specification to automatic field numbering",
				error("'{0:{}}'.format(1, 2)"));
	}

	@Test
	void listAppendExtendInsert() {
		assertEquals("[1, 2, 3]", run("xs = [1, 2]\nxs.append(3)\nprint(xs)"));
		assertEquals("[1, 2, 3, 'a', 'b']", run("xs = [1]\nxs.extend((2, 3))\nxs.extend('ab')\nprint(xs)"));
		assertEquals("'int' object is not iterable", error("[1].extend(5)"));
		assertEquals("[0, 1, 2, 9, 3, 7]",
				run("xs = [1, 3]\nxs.insert(1, 2)\nxs.insert(-1, 9)\nxs.insert(100, 7)\nxs.insert(-100, 0)\nprint(xs)"));
		assertEquals("None", eval("[].append(1)"));
	}

	@Test
	void listPop() {
		assertEquals("3 1 [2]", run("xs = [1, 2, 3]\nprint(xs.pop(), xs.pop(0), xs)"));
		assertEquals("pop from empty list", error("[].pop()"));
		assertEquals("pop index out of range", error("[1].pop(5)"));
		assertEquals("'str' object cannot be interpreted as an integer", error("[1].pop('a')"));
	}

	@Test
	void listRemoveAndIndex() {
		assertEquals("[2, 1]", run("xs = [1, 2, 1]\nxs.remove(1)\nprint(xs)"));
		assertEquals("list.remove(x): x not in list", error("[1].remove(2)"));
		assertEquals("1 2", eval("[1, 2, 3].index(2), [1, 2, 1].index(1, 1)"));
		assertEquals("list.index(x): x not in list", error("[1].index(2)"));
	}

	@Test
	void listSort() {
		assertEquals("None [1, 2, 3]", run("xs = [3, 1, 2]\nprint(xs.sort(), xs)"));
		assertEquals("[3, 2, 1]", run("xs = [3, 1, 2]\nxs.sort(reverse=True)\nprint(xs)"));
		assertEquals("'<' not supported between instances of 'str' and 'int'", error("[1, 'a'].sort()"));
	}

	@Test
	void dictGetPopAndSetdefault() {
		assertEquals("1 None 0", run("d = {'a': 1}\nprint(d.get('a'), d.get('b'), d.get('b', 0))"));
		assertEquals("1 0 {'b': 2}", run("d = {'a': 1, 'b': 2}\nprint(d.pop('a'), d.pop('x', 0), d)"));
		assertEquals("key 'x' is not in the dict", error("{}.pop('x')"));
		assertEquals("[] None {'a': [], 'b': None}", run("d = {}\nprint(d.setdefault('a', []), d.setdefault('b'), d)"));
		assertEquals("{'a': [1, 2]}",
				run("d = {}\nd.setdefault('a', []).append(1)\nd.setdefault('a', []).append(2)\nprint(d)"));
		assertEquals("cannot use 'list' as a dict key (unhashable type: 'list')", error("{}.get([])"));
	}

	@Test
	void dictKeysValuesAndItemsAreLiveViews() {
		assertEquals("dict_keys(['a', 'b']) dict_values([1, 2]) dict_items([('a', 1), ('b', 2)])",
				run("d = {'a': 1, 'b': 2}\nprint(d.keys(), d.values(), d.items())"));
		assertEquals("dict_keys(['a', 'b']) 2 True ['a', 'b']",
				run("d = {'a': 1}\nk = d.keys()\nd['b'] = 2\nprint(k, len(k), 'b' in k, list(k))"));
		assertEquals("a 1", run("for k, v in {'a': 1}.items():\n    print(k, v)"));
		assertEquals("True True", eval("('a', 1) in {'a': 1}.items(), 1 in {'a': 1}.values()"));
	}

	@Test
	void anUnknownMethodNamesTheType() {
		assertEquals("'list' object has no attribute 'push'", error("[].push(1)"));
	}
}
