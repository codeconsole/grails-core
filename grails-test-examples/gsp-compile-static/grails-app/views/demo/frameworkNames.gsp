<!doctype html>
<html>
<head><title>framework names</title></head>
<body>
<p id="controller">${controllerName}</p>
<p id="action">${actionName}</p>
<p id="flash">${flash.message}</p>
<p id="param">${params.int('n') ?: 0}</p>
<p id="link"><g:createLink controller="demo" action="declared"/></p>
</body>
</html>
