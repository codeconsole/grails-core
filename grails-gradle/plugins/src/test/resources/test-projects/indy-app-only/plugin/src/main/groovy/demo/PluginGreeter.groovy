package demo

class PluginGreeter {

    String greet(String name) {
        def shouted = name.toUpperCase()
        'hello ' + shouted
    }
}
