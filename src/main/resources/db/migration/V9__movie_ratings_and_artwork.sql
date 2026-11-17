-- Real movie data for the demo (from TMDB when configured): a rating with its vote count, a wide
-- backdrop image for the movie page, and the TMDB id the data came from. All optional: movies an
-- admin adds by hand, and the built-in demo movies, have none of these.
alter table movies
    add column tmdb_id      integer,
    add column rating       double precision check (rating between 0 and 10),
    add column vote_count   integer check (vote_count >= 0),
    add column backdrop_url varchar(500);
