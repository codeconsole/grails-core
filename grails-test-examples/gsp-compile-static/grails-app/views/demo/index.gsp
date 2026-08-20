<%@ page model="String title; java.util.List<gspstatic.Book> books" %>
<!doctype html>
<html>
<head><title>${title}</title></head>
<body>
<h1>${title}</h1>
<g:def type="int" var="total" value="${0}"/>
<ul>
<g:each in="${books.findAll { it.pages > 0 }}" var="book" status="i">
    <li id="book-${i}">${book.title} has ${book.pages} pages</li>
</g:each>
</ul>
<p id="count">${books.size()}</p>
<p id="longest">${books.max { it.pages }.title}</p>
<p id="shout"><demo:shout text="quiet"/></p>
</body>
</html>
