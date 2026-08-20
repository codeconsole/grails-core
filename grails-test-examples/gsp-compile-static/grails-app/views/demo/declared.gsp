<%@ page model="gspstatic.Book book; int count" %>
<!doctype html>
<html>
<head><title>${book.title}</title></head>
<body>
<p id="title">${book.title}</p>
<p id="pages">${book.pages}</p>
<p id="total">${book.pages * count}</p>
<g:set type="java.lang.String" var="upper" value="${book.title.toUpperCase()}"/>
<p id="upper">${upper}</p>
</body>
</html>
