# Error Prone's CLASS-retained IncompatibleModifiers annotation references this
# javac-only enum. Android does not provide it, and the test runner never uses it.
-dontwarn javax.lang.model.element.Modifier
