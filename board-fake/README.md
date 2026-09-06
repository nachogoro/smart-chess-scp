# board-fake

`BoardModule` implementation that reads board state from a text file on disk. Used for development and testing without a physical chess board.

The file's first line declares what the board can do: its capability level (`full`, `color` or
`occupancy`), followed by `moves` if it can move pieces itself and `places` if it can also put down
a piece from off the board. A board that moves pieces rewrites the file when asked to, so whatever
else displays the file reads it back afterwards.
