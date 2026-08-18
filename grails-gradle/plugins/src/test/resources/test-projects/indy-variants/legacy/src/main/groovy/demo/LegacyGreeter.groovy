package demo

class LegacyGreeter {

    String greet(String name) {
        def shouted = name.toUpperCase()
        'hello ' + shouted
    }
}
