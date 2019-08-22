BitTorrent Client
=================

Development
-----------

VS Code (Metals) is supported out of the box.

For IntelliJ IDEA:
```sh
$ ./mill mill.scalalib.GenIdea/idea
```

Run tests:
```sh
$ ./mill _.test
```

Run
-----

```sh
$ ./mill cli.assembly
$ java -jar out/cli/assembly/dest/out.jar
```