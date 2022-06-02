# Playing with Primes in Clojure

My friend Andrew recently dicovered the joys of programming, and
shared his cool Python code for testing numbers for primality. I
wanted to encourage this enthusiasm, and make sure that he learned
about functional programming before his mind got too stuck in the ruts
of C-style imperative code (which makes up the bulk of examples out
there), so I decided to play around with writing some of my own
examples for him.

I think one of the best languages for exploring functional programming
today is probably [Racket](https://racket-lang.org), but I haven't had
time to learn that yet myself. The pinnacle in many ways is
[Haskell](https://www.haskell.org) but that's not nearly as
approachable, and I would be even more lost trying to introduce
someone there. But I have had the privilege of using
[Clojure](https://clojure.org) for my professional work for the past
several years, and find it a lovely, practical modern Lisp with a
focus on simplicity and functional programming, and with a great
interoperability story for the Java Virtual Machine environment, which
is the indutrial-strength platform we use to deliver our products.
(It's also been perfect for my open-source projects in areas like
[light show control](https://github.com/Deep-Symmetry/afterglow#afterglow)
and [DJ performance
synchronization](https://github.com/Deep-Symmetry/beat-link-trigger#beat-link-trigger).)
So I'm well equipped to guide a tour through finding prime numbers in
Clojure!

## Building Blocks

Since Clojure is a Lisp, it looks very different than C-like languages
(including Python). The first thing to get used to is that pretty much
everything is an expression, formed by grouping a list of symbols
inside parentheses. The first symbol specifies _what you want to do_
and is generally a function name (or a macro, or a special form, but
we won't need to worry about those details in this discussion). Then
you follow that with _what you want to do it to_. So for example, to
divide 10 by 2, you would write:

```clojure
(/ 10 2)
```

And you would see the result, `5`. Not too surprising. A bit more
surprising is what happens if you try dividing 10 by 3 (from now on,
to save space in these examples, I am going to show what I type at the
Clojure REPL prompt, followed by a comment prefixed with `;; => ` that
shows the response):

```clojure
(/ 10 3)
;; => 10/3
```

Wait, `10/3`, what witchcraft is this? It didn't give me an answer, or
at least not the kind I expected! In fact, Clojure is biased towards
retaining as much precision as possible, so when you ask it to perform
a division whose result does not have an exact integer representation,
it returns a rational value which is exactly equal to what you wanted.
You can continue to use that result in further mathematical operations
with no loss of precision, or you can tell Clojure you want a
floating-point approximation of the result, like you would get in most
languages, like so:

```clojure
(float (/ 10 3))
;; => 3.3333333
```

So, for finding primes, what we want to know is whether a number
evenly divides another. The `rem` function helps with that, giving us
the remainder:

```clojure
(rem 10 2)
;; => 0

(rem 10 3)
;; => 1
```

We can combine that with the `zero?` predicate (a predicate is a
function that returns `true` or `false` given its input) to build up
our test for whether one number evenly divides another:

```clojure
(zero? (rem 10 3))
false

(zero? (rem 10 2))
true
```

## Building our Own Blocks

Great, we get `true` when the second number evenly divides the first,
and `false` otherwise. This is useful enough that I want to give it a
name, to be able to use it in other expressions. So I will define it
as a function, using the `defn` macro:

```clojure
(defn divides?
  "Returns true when the numerator can be evenly divided by the
  denominator."
  [numerator denominator]
  (zero? (rem numerator denominator)))
;; => #'user/divides?
```

There is a little more structure to this than we've encountered so
far, but hopefully it's still easy enough to figure out what is going
on. We are defining a new function named `divides?`. The string that
follows the function name is an optional "doc string", which helps
explain how to use the function, both when reading the definition, and
also when you are typing at the REPL (this word comes from "Read,
Eval, Print, Loop", the core of interactive programming with a Lisp
like Clojure). At a REPL prompt you can type `(doc divides?)` and get
that explanatory text:

```clojure
(doc divides?)
;; => -------------------------
;; => user/divides?
;; => ([numerator denominator])
;; =>   Returns true when the numerator can be evenly divided by the
;; =>   denominator.
```

Following the doc string is the parameter list, which specifies what
the function expects to be given when called, in our case two values
which we name `numerator` and `denominator`. And then finally, the
last line is the actual body of the function, which uses the `zero?`
predicate and `rem` function as we did in our earlier examples. The
return value we saw at the REPL simply reported that the function
`divides?` had been created in the `user` namespace, which goes a bit
far afield from what we are talking about today.

With this in place, we can try out our new function. It works just as
you'd expect:

```clojure
(divides? 10 2)
;; => true

(divides? 10 3)
;; => false
```

Incidentally, we can use `doc` on the other functions we've been using
up to this point:

```clojure
(doc zero?)
;; => -------------------------
;; => clojure.core/zero?
;; => ([num])
;; =>  Returns true if num is zero, else false
```

And now we are ready to build our prime-tester! As in Andrew's example
I want to start by verifying that we were given a positive integer.
There are two built-in predicates we can use for that, `integer?` and
`pos?`. All that remains is to check whether there is any number
ranging from two up to the square root of our candidate number that
evenly divides it. If not, then we can be sure it is prime.

While in imperative languages you do this kind of thing by writing an
explicit loop that assigns a variable to hold each value you want to
consider, in a functional language you instead filter sequences of
values using predicates. So let's start by figuring out how to express
the sequence of values we want to check. To test if ten is prime, the
range of values we need to test if it is divisible by starts at two,
and goes up to three (the largest integer that is less than the square
root of ten). There is a built-in function `range` that gives a range
of values. If we try it out with the square root of our candidate
value, we see it seems _almost_ give us the numbers we want:

```clojure
(range (Math/sqrt 10))
;; => (0 1 2 3 4)
```

We have a couple of extra values at the beginning, because `range`
defaults to starting with zero, and although it goes far enough in
this case, because it gives you values up to _but not including_ the
upper bound you supply, it would not work if we gave it a perfect
square as its input. Let's tackle the first problem first: We could
use the `drop` function to get rid of the first two values, but if we
check the `doc` for `range` we find that there is also another way to
call it, which lets us supply the starting value too:

```clojure
(doc range)
-------------------------
clojure.core/range
;; => ([] [end] [start end] [start end step])
;; =>   Returns a lazy seq of nums from start (inclusive) to end
;; =>   (exclusive), by step, where start defaults to 0, step to 1, and end to
;; =>   infinity. When step is equal to 0, returns an infinite sequence of
;; =>   start. When start is equal to end, returns empty list.
```

So `(range 2 (Math/sqrt 10))` gets us almost there; it works great for ten:

```clojure
(range 2 (Math/sqrt 10))
;; => (2 3)
```

But if we try it with 9, which is a perfect square, we don't get the
value 3, which is its square root, and which we definitely do need to
test as a factor:

```clojure
(range 2 (Math/sqrt 10))
;; => (2)
```

To fix that, we will round the result of our square root operation
down to the closest integer less than or equal to it, using the
`Math/floor` function, and then add one to that:

```clojure
(range 2 (inc (Math/floor (Math/sqrt 9))))
(2 3)
```

That slightly more complex formula also works for ten:

```clojure
(range 2 (inc (Math/floor (Math/sqrt 10))))
(2 3)
```

Ok, that gives us the numbers we want to check if ten is divisible by.
Ten is prime if it is not divisible by any of those numbers. And the
way we express that in Clojure is almost shockingly concise and
readable, once you get over all the parentheses. At this point I want
to show you the finished `prime?` function, and explain how the final
pieces fit together:

```clojure
(defn prime?
  "Returns true when `n` is prime."
  [n]
  (and (integer? n)
       (pos? n)
       (not-any? (partial divides? n) (range 2 (inc (Math/floor (Math/sqrt n)))))))
```

We already looked at `defn`, the doc string, and argument list. Our
`prime?` function takes a single argument `n`, and (as suggested by
the question mark at the end of the name) is a predicate, returning
`true` or `false`. In the body, we use a new `and` function, which, as
its name suggests, returns `true` if all of its arguments are
themselves true. In fact, it stops evaluating and returns false as
soon as it encounters a false argument, so we don't have to worry
about division by zero or other problems, because the first two things
we check are that `n` is a positive integer.

That last line is where the rubber meets the road. And at first glance
it might seem confusing. But notice that the second half, the `range`
expression, we have already explored. That returns a sequence of
integers ranging from 2 to the square root of `n`. It is used as the
second argument of the `not-any?` function. `not-any?` takes a
predicate and a collection, and returns `true` if no elements of the
collection satisfy the predicate. The collection we gave it is the
list of numbers we want to check as potential divisors of our
candidate prime number. So we need a predicate that returns `true` if
the potential divisor evenly divides `n`.

In other words, we want a function that takes a single number, and
returns `true` if that number evenly divides `n`, whatever `n`
happened to be when `prime?` was called. One of the really powerful
things about functional languages is that they make it very easy to
create, modify, and pass around functions, and our little bit of code
here is a perfect example of that. The `partial` function is an
example of a _higher order function_, which means a function that
manipulates other functions. You give `partial` the name of a function
that you want to work with, and some arguments, and it returns the
result of _partially applying_ those arguments to the function.

> What?

Well, let me be more conrete. We have already defined a function
`divides?` that takes two arguments. We want a function that acts just
like that, only with the first argument, the numerator, already filled
in, because we want a predicate that takes one argument and tests
whether that value evenly divides `n`. We can do exactly that:
`(partial divides? n)` creates a new function that calls `divides?`
with the values `n` and whatever you pass to this new function. So
that is exactly what we need as a predicate for `not-any?` to use. Our
`prime?` function will return `true` if no integer from 2 to the
square root of `n` can evenly divide `n`. And in so few words! The
explanation is far bigger than the code itself, even counting the doc
strings. Let's try it out!

```clojure
(prime? 10)
;; => false

(prime? 11)
;; => true

(prime? 1001)
;; => false

user=> (prime? 1009)
;; => true
```

## Infinite, Lazy Blocks

So that was to show Andrew what his prime tester would look like in
Clojure. But this is just where the fun begins! Now that we have a
nice `prime?` predicate, we can use it to easily build the list of all
prime numbers:

```clojure
(def primes
  "The prime numbers."
  (filter prime? (range)))
```

The `filter` function takes a predicate and a collection, and returns
a new collection that contains only those elements for which the
predicate was true. So in this case, we supply all the natural numbers
for our range (when you give no arguments to `range`, the lower bound
is zero, and there is no upper bound), and filter out anything that
isn't prime.

> Whoa, James, you're crazy. That can't work. I know I bought a fancy
> computer, but it doesn't have enough memory to store _all_ numbers,
> and even if it could, even with my fast processor, it would take too
> long to figure out which elements in that infinite sequence were
> prime!

Here's the crazy thing, it _does_ work! And the reason it does is that
Clojure supports _lazy sequences_, meaning the values are not produced
until you actually ask for them. So we can quite happily define
infinite sequences, and only pay for the production of the values we
actually want to work with. So yes, if you tried to print out the
value of `primes`, your computer would chunk away until it ran out of
memory, but we can use the `take` function to look at as many as we
want to without that problem. Here are the first ten primes:

```clojure
(take 10 primes)
;; => (1 2 3 5 7 11 13 17 19 23)
```

And, actually, this reveals a mistake we made early on. Technically, 1
is not considered a prime number, and including it in this list is
going to break some of the fancy things we are about to do, so let's
go back and fix this. (I left this change until now, because I wanted
the code to exactly parallel Andrew's Python example, but we are going
to start diverging a bit.) Here is the revised version of `prime?`:

```clojure
(defn prime?
  "Returns true when `n` is prime."
  [n]
  (and (integer? n)
       (> n 1)
       (not-any? (partial divides? n) (range 2 (inc (Math/floor (Math/sqrt n)))))))
```

> I will include a complete copy of the final version of all this code
> at the end of this article for people who want to examine it as a
> whole, or load it into their own REPL and play.

Reloading everything with that change in place, we get this more
traditional list of the first ten primes:

```clojure
(take 10 primes)
(2 3 5 7 11 13 17 19 23 29)
```

And we can combine `take` with `drop` to skip to the part of the
sequence we're interested in. Here's how we would find out what the
thousandth prime is:

```clojure
(take 1 (drop 999 primes))
;; => (7919)
```

Or what about the ten-thousandth?

```clojure
(take 1 (drop 9999 primes))
;; => (104729)
```

But that was starting to make my computer work hard. There was a
noticeable delay between when I hit `return` and when I got the answer
back. Interestingly, however, if I ask it a second time, I get the
answer instantly. That's because the `primes` sequence remembers
values that have aleady been calculated, and only has to do work when
you ask it to go out beyond what it has already figured out.

Let's take a look at exactly how hard it is working, and start
exploring some interesting ways we can calculate primes faster. If I
quit the Clojure REPL, then reload my functions, I can time how long
getting the ten-thousandth prime took using the `time` function.

At first I was getting utterly bogus values by trying it directly like
this:

```clojure
(time (take 1 (drop 9999 primes)))
;; => "Elapsed time: 0.02719 msecs"
```

But then I remembered one of the pitfalls of working with lazy
sequences. In the above attempt, we never _did anything_ with the
expression, so it stayed lazy, and the prime number sequence never got
built. So to force it to really do the work, I added a call to
`vec`, which converts the result to a non-lazy vector, thereby forcing
evaluation. Armed with that better approach, and starting with a fresh
Clojure REPL, I got the following results:

```clojure
(time (vec (take 1 (drop 9999 primes))))
;; => "Elapsed time: 28318.878726 msecs"
;; => [104723]
```

The square brackets around the result show it is a vector rather than
a sequence, and you can see it took nearly thirty seconds to come up
with the answer.

Running it a second time it already knew the primes up to that point,
so it went vastly faster:

```clojure
(time (vec (take 1 (drop 9999 primes))))
;; => "Elapsed time: 1.320859 msecs"
;; => [104723]
```

No noticeable delay. Lazy sequences are cool! And we can use them in
really powerful ways, as we will now explore.

## Getting Even Smarter About It

That was some very compact code that gave us a nice list of prime
numbers. But we can do even better! Now that we have this sequence of
prime numbers, we can make use of it in our definition of `prime?`
itself, because it is actually redundant to try dividing `n` by
_every_ number from `2` to `sqrt(n)`, we only need to test the _prime_
numbers in that range.

To make this convenient, let's define a couple of new functions.
First, a predicate that will tell us whether the prime factor we are
considering is small enough that we need to test it:

```clojure
(defn sqrt-or-less?
  "Returns true when `candidate` is less than or equal to the
  square root of `n`."
  [n candidate]
  (<= candidate (Math/sqrt n)))
```

Then, a function that returns the set of prime numbers we need to try
dividing our number by, in order to see if it is prime. In other
words, all prime numbers from 2 up to the square root of our number.
Because this function is going to use our `primes` sequence, but we
can't define `primes` until later because it needs to use this
function, we have to promise the Clojure compiler that `primes` is a
value we will define later on. Then we can write the new function
using it:

```clojure
(declare primes)

(defn potential-prime-factors
  "Returns the prime numbers from 2 up to the square root of `n`."
  [n]
  (take-while (partial sqrt-or-less? n) primes))
```

This introduces another new function, `take-while`, which is like
`take` but instead of knowing in advance how many values we want, we
can supply a predicate, and we will get back all the values up to the
one for which that predicate is no longer true. The predicate we want
is one that is true when the number is small enough to potentially be
a prime factor of `n`, so that means it is less than or equal to the
square root of `n`. We once again use the `partial` higher-order
function to build that predicate, by "pre-filling" in the value of `n`
as the first argument of our `sqrt-or-less?` predicate, and then we
keep feeding it prime numbers until we reach one that is too big.

> I could have done this next bit without adding those two new helper
> functions, but that would require using Clojure's "function
> literals" to define functions right where I am using them, and that
> is more of a detour than makes sense for this introduction. And
> sometimes spelling things out and naming them makes for a more
> readable solution to my future self anyway.

Finally, a redefinition of our `prime?` function, taking advantage of
these new helper functions:

```clojure
(defn prime?
  "Returns true if `n` is prime."
  [n]
  (and (integer? n)
       (> n 1)
       (not-any? (partial divides? n) (potential-prime-factors n))))
```

It's now even more self-explanatory, thanks to the nicely named helper
functions. But more importantly, is it faster? If I start in a fresh
Clojure REPL, load the code, and time it, I see...

```clojure
(time (vec (take 1 (drop 9999 primes))))
;; => "Elapsed time: 121.442485 msecs"
;; => [104729]
```

Indeed! A tenth of a second is definitely faster than thirty. Nice.
And what a fun exploration this has been. Thanks, Andrew! Even though
I kind of knew where I was hoping to end up, I was impressed by just
how perfectly lazy sequences fit this problem. I was able to define
the prime number sequence using a function that depends on the prime
number sequence, and since things are not evaluated until they are
needed, that works perfectly, and only does work once, the first time
it is needed.

> When I first posted these examples, I had misremembered how big you
> need to go when testing potential prime factors, and was going all
> the way up to half of `n`, rather than to its square root. That
> still saved time, taking just over six seconds, but a tenth of a
> second is way better. Thanks to my newest coworker Sarah for
> reminding me of this important fact.

Here is the full code in its final version, as promised:

```clojure
(defn divides?
  "Returns true when the numerator can be evenly divided by the
  denominator."
  [numerator denominator]
  (zero? (rem numerator denominator)))

(defn sqrt-or-less?
  "Returns true when `candidate` is less than or equal to the
  square root of `n`."
  [n candidate]
  (<= candidate (Math/sqrt n)))

(declare primes)

(defn potential-prime-factors
  "Returns the prime numbers from 2 up to the square root of `n`."
  [n]
  (take-while (partial sqrt-or-less? n) primes))

(defn prime?
  "Returns true if `n` is prime."
  [n]
  (and (integer? n)
       (> n 1)
       (not-any? (partial divides? n) (potential-prime-factors n))))

(def primes
  "The prime numbers."
  (filter prime? (range)))

```
