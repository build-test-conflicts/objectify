package com.googlecode.objectify;

import com.google.appengine.api.datastore.AsyncDatastoreService;
import com.google.appengine.api.datastore.DatastoreService.KeyRangeState;
import com.google.appengine.api.datastore.DatastoreServiceConfig;
import com.google.appengine.api.datastore.DatastoreServiceFactory;
import com.googlecode.objectify.cache.CachingAsyncDatastoreService;
import com.googlecode.objectify.cache.EntityMemcache;
import com.googlecode.objectify.cache.PendingFutures;
import com.googlecode.objectify.impl.CacheControlImpl;
import com.googlecode.objectify.impl.EntityMemcacheStats;
import com.googlecode.objectify.impl.EntityMetadata;
import com.googlecode.objectify.impl.Forge;
import com.googlecode.objectify.impl.Keys;
import com.googlecode.objectify.impl.ObjectifyImpl;
import com.googlecode.objectify.impl.Registrar;
import com.googlecode.objectify.impl.TypeUtils;
import com.googlecode.objectify.impl.translate.Translators;
import com.googlecode.objectify.util.Closeable;

import java.lang.reflect.Constructor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * <p>Factory which allows us to construct implementations of the Objectify interface.
 * You should usually use the ObjectifyService to access Objectify.</p>
 *
 * <p>ObjectifyFactory is designed to be subclassed; much default behavior can be changed
 * by overriding methods.  In particular, see createObjectify(), construct(), getAsyncDatastoreService().</p>
 *
 * @author Jeff Schnitzer <jeff@infohazard.org>
 */
public class ObjectifyFactory implements Forge
{
	/** Default memcache namespace */
	public static final String MEMCACHE_NAMESPACE = "ObjectifyCache";

	/**
	 * Thread local stack of Objectify instances corresponding to transaction depth
	 */
	private final ThreadLocal<Deque<Objectify>> stacks = ThreadLocal.withInitial(ArrayDeque::new);

	/** Encapsulates entity registration info */
	protected Registrar registrar = new Registrar(this);

	/** Some useful bits for working with keys */
	protected Keys keys = new Keys(registrar);

	/** All the various loaders */
	protected Translators translators = new Translators(this);

	/** Tracks stats */
	protected EntityMemcacheStats memcacheStats = new EntityMemcacheStats();

	/** Manages caching of entities at a low level */
	protected EntityMemcache entityMemcache = new EntityMemcache(MEMCACHE_NAMESPACE, new CacheControlImpl(this), this.memcacheStats);

	/**
	 * <p>Construct an instance of the specified type.  Objectify uses this method whenever possible to create
	 * instances of entities, condition classes, or other types; by overriding this method you can substitute Guice or other
	 * dependency injection mechanisms.  By default it constructs with a simple no-args constructor.</p>
	 */
	@Override
	public <T> T construct(final Class<T> type) {
		// We do this instead of calling newInstance directly because this lets us work around accessiblity
		final Constructor<T> ctor = TypeUtils.getNoArgConstructor(type);
		return TypeUtils.newInstance(ctor);
	}

	/**
	 * <p>Construct a collection of the specified type and the specified size for use on a POJO field.  You can override
	 * this with Guice or whatnot.</p>
	 *
	 * <p>The default is to call construct(Class), with one twist - if a Set, SortedSet, or List interface is presented,
	 * Objectify will construct a HashSet, TreeSet, or ArrayList (respectively).  If you override this method with
	 * dependency injection and you use uninitialized fields of these interface types in your entity pojos, you will
	 * need to bind these interfaces to concrete types.</p>
	 */
	@SuppressWarnings("unchecked")
	public <T extends Collection<?>> T constructCollection(final Class<T> type, final int size) {
		if ((Class<?>)type == List.class || (Class<?>)type == Collection.class)
			return (T)new ArrayList<>(size);
		else if ((Class<?>)type == Set.class)
			return (T)new HashSet<>((int)(size * 1.5));
		else if ((Class<?>)type == SortedSet.class)
			return (T)new TreeSet<>();
		else
			return construct(type);
	}

	/**
	 * <p>Construct a map of the specified type for use on a POJO field.  You can override this with Guice or whatnot.</p>
	 *
	 * <p>The default is to call construct(Class), with one twist - if a Map or SortedMap List interface is presented,
	 * Objectify will construct a HashMap or TreeMap (respectively).  If you override this method with
	 * dependency injection and you use uninitialized fields of these interface types in your entity pojos, you will
	 * need to bind these interfaces to concrete types.</p>
	 */
	@SuppressWarnings("unchecked")
	public <T extends Map<?, ?>> T constructMap(final Class<T> type) {
		if ((Class<?>)type == Map.class)
			return (T)new HashMap<>();
		else if ((Class<?>)type == SortedMap.class)
			return (T)new TreeMap<>();
		else
			return construct(type);
	}

	/**
	 * Get an AsyncDatastoreService facade appropriate to the options.  All Objectify
	 * datastore interaction goes through an AsyncDatastoreService.  This might or
	 * might not produce a CachingAsyncDatastoreService.
	 *
	 * @return an AsyncDatastoreService configured per the specified options.
	 */
	public AsyncDatastoreService createAsyncDatastoreService(final DatastoreServiceConfig cfg, final boolean globalCache) {
		final AsyncDatastoreService ads = this.createRawAsyncDatastoreService(cfg);

		if (globalCache && this.registrar.isCacheEnabled())
			return new CachingAsyncDatastoreService(ads, this.entityMemcache);
		else
			return ads;
	}

	/**
	 * You can override this to add behavior at the raw datastoreservice level.
	 */
	protected AsyncDatastoreService createRawAsyncDatastoreService(final DatastoreServiceConfig cfg) {
		return DatastoreServiceFactory.getAsyncDatastoreService(cfg);
	}

	/**
	 * This is the beginning of any Objectify session.  It creates an Objectify instance with the default
	 * options, unless you override this method to alter the options.  You can also override this method
	 * to produce a wholly different Objectify implementation (possibly using ObjectifyWrapper).
	 *
	 * <p>The default options are:</p>
	 *
	 * <ul>
	 * <li>Do NOT begin a transaction.</li>
	 * <li>DO use a global cache.</li>
	 * <li>Use STRONG consistency.</li>
	 * <li>Apply no deadline to calls.</li>
	 * </ul>
	 *
	 * <p>Note that when using Objectify you will almost never directly call this method.  Instead you
	 * should call the static ofy() method on ObjectifyService.</p>
	 *
	 * @return a new Objectify instance
	 *
	 * @deprecated This method is a holdover from the 1.x days and will be removed in the future.
	 * 		Clients should use {@link ObjectifyService#ofy()} to obtain Objectify instances.
	 */
	@Deprecated
	public Objectify begin() {
		return new ObjectifyImpl(this);
	}

	/**
	 * <p>All POJO entity classes which are to be managed by Objectify
	 * must be registered first.  This method must be called in a single-threaded
	 * mode sometime around application initialization.</p>
	 *
	 * <p>Any extra translators must be added to the Translators *before*
	 * entity classes are registered.</p>
	 *
	 * <p>Attempts to re-register entity classes are ignored.</p>
	 */
	public <T> void register(final Class<T> clazz) {
		this.registrar.register(clazz);
	}

	/**
	 * Get the object that tracks memcache stats.
	 */
	public EntityMemcacheStats getMemcacheStats() { return this.memcacheStats; }

	/**
	 * Sets the error handler for the main memcache object.
	 */
	@SuppressWarnings("deprecation")
	public void setMemcacheErrorHandler(final com.google.appengine.api.memcache.ErrorHandler handler) {
		this.entityMemcache.setErrorHandler(handler);
	}

	//
	// Stuff which should only be necessary internally, but might be useful to others.
	//

	/**
	 * @return the metadata for a kind of typed object
	 * @throws IllegalArgumentException if the kind has not been registered
	 */
	public <T> EntityMetadata<T> getMetadata(final Class<T> clazz) throws IllegalArgumentException {
		return this.registrar.getMetadataSafe(clazz);
	}

	/**
	 * @return the metadata for a kind of entity based on its key
	 * @throws IllegalArgumentException if the kind has not been registered
	 */
	public <T> EntityMetadata<T> getMetadata(final com.google.appengine.api.datastore.Key key) throws IllegalArgumentException {
		return this.registrar.getMetadataSafe(key.getKind());
	}

	/**
	 * @return the metadata for a kind of entity based on its key
	 * @throws IllegalArgumentException if the kind has not been registered
	 */
	public <T> EntityMetadata<T> getMetadata(final Key<T> key) throws IllegalArgumentException {
		return this.registrar.getMetadataSafe(key.getKind());
	}

	/**
	 * Gets metadata for the specified kind, returning null if nothing registered. This method is not like
	 * the others because it returns null instead of throwing an exception if the kind is not found.
	 * @return null if the kind is not registered.
	 */
	public <T> EntityMetadata<T> getMetadata(final String kind) {
		return this.registrar.getMetadata(kind);
	}

	/**
	 * Named differently so you don't accidentally use the Object form
	 * @return the metadata for a kind of typed object.
	 * @throws IllegalArgumentException if the kind has not been registered
	 */
	@SuppressWarnings("unchecked")
	public <T> EntityMetadata<T> getMetadataForEntity(final T obj) throws IllegalArgumentException {
		// Type erasure sucks
		return (EntityMetadata<T>)this.getMetadata(obj.getClass());
	}

	/**
	 * Allocates a single id from the allocator for the specified kind.  Safe to use in concert
	 * with the automatic generator.  This is just a convenience method for allocateIds().
	 *
	 * @param clazz must be a registered entity class with a Long or long id field.
	 * @return a key with an id that is unique to the kind
	 */
	public <T> Key<T> allocateId(final Class<T> clazz) {
		return allocateIds(clazz, 1).iterator().next();
	}

	/**
	 * Allocates a single id from the allocator for the specified kind.  Safe to use in concert
	 * with the automatic generator.  This is just a convenience method for allocateIds().
	 *
	 * Note that the id is only unique within the parent, not across the entire kind.
	 *
	 * @param parentKeyOrEntity must be a legitimate parent for the class type.  It need not
	 * point to an existent entity, but it must be the correct type for clazz.
	 * @param clazz must be a registered entity class with a Long or long id field, and
	 * a parent key of the correct type.
	 * @return a key with a new id unique to the kind and parent
	 */
	public <T> Key<T> allocateId(final Object parentKeyOrEntity, final Class<T> clazz) {
		return allocateIds(parentKeyOrEntity, clazz, 1).iterator().next();
	}

	/**
	 * Preallocate a contiguous range of unique ids within the namespace of the
	 * specified entity class.  These ids can be used in concert with the normal
	 * automatic allocation of ids when put()ing entities with null Long id fields.
	 *
	 * @param clazz must be a registered entity class with a Long or long id field.
	 * @param num must be >= 1 and <= 1 billion
	 */
	public <T> KeyRange<T> allocateIds(final Class<T> clazz, final long num) {
		// Feels a little weird going directly to the DatastoreServiceFactory but the
		// allocateIds() method really is optionless.
		final String kind = Key.getKind(clazz);
		return new KeyRange<>(DatastoreServiceFactory.getDatastoreService().allocateIds(kind, num));
	}

	/**
	 * Preallocate a contiguous range of unique ids within the namespace of the
	 * specified entity class and the parent key.  These ids can be used in concert with the normal
	 * automatic allocation of ids when put()ing entities with null Long id fields.
	 *
	 * @param parentKeyOrEntity must be a legitimate parent for the class type.  It need not
	 * point to an existent entity, but it must be the correct type for clazz.
	 * @param clazz must be a registered entity class with a Long or long id field, and
	 * a parent key of the correct type.
	 * @param num must be >= 1 and <= 1 billion
	 */
	public <T> KeyRange<T> allocateIds(final Object parentKeyOrEntity, final Class<T> clazz, final long num) {
		final Key<?> parent = keys().anythingToKey(parentKeyOrEntity);
		final String kind = Key.getKind(clazz);

		// Feels a little weird going directly to the DatastoreServiceFactory but the
		// allocateIds() method really is optionless.
		return new KeyRange<>(DatastoreServiceFactory.getDatastoreService().allocateIds(parent.getRaw(), kind, num));
	}

	/**
	 * Allocates a user-specified contiguous range of unique IDs, preventing the allocator from
	 * giving them out to entities (with autogeneration) or other calls to allocate methods.
	 * This lets you specify a specific range to block out (for example, you are bulk-loading a
	 * collection of pre-existing entities).  If you don't care about what id is allocated, use
	 * one of the other allocate methods.
	 */
	public <T> KeyRangeState allocateIdRange(final KeyRange<T> range) {
		return DatastoreServiceFactory.getDatastoreService().allocateIdRange(range.getRaw());
	}

	/**
	 * <p>Gets the master list of all registered TranslatorFactory objects.  By adding Translators, Objectify
	 * can process additional field types which are not part of the standard GAE SDK.  <b>You must
	 * add translators *before* registering entity pojo classes.</b></p>
	 *
	 * @return the repository of TranslatorFactory objects, to which you can optionally add translators
	 */
	public Translators getTranslators() {
		return this.translators;
	}

	/**
	 * Some tools for working with keys. This is an internal Objectify API and subject to change without
	 * notice. You probably want the Key.create() methods instead.
	 */
	public Keys keys() {
		return keys;
	}

	/**
	 * The method to call at any time to get the current Objectify, which may change depending on txn context.
	 * Normally you should use the static {@link ObjectifyService#ofy()} which calls this method.
	 */
	public Objectify ofy() {
		final Deque<Objectify> stack = stacks.get();

		if (stack.isEmpty())
			throw new IllegalStateException("You have not started an Objectify context. You are probably missing the " +
					"ObjectifyFilter. If you are not running in the context of an http request, see the " +
					"ObjectifyService.run() method.");

		return stack.getLast();
	}

	/**
	 * <p>Start a scope of work. This is the outermost scope of work, typically created by the ObjectifyFilter
	 * or by one of the methods on ObjectifyService. You need one of these to do anything at all.</p>
	 */
	Closeable createRootScope() {
		final Deque<Objectify> stack = stacks.get();

		// Request forwarding in the container runs all the filters again, including the ObjectifyFilter. Since we
		// have established a context already, we can't just throw an exception. We can't even really warn. Let's
		// just give them a new context; the bummer is that if programmers screw up and fail to close the context,
		// we have no way of warning them about the leak.
		//if (!stack.isEmpty())
		//	throw new IllegalStateException("You already have an initial Objectify context. Perhaps you want to use the ofy() method?");

		final Objectify ofy = this.begin();

		stack.add(ofy);

		return () -> {
			if (stack.isEmpty())
				throw new IllegalStateException("You have already destroyed the Objectify context.");

			// Same comment as above - we can't make claims about the state of the stack beacuse of dispatch forwarding
			//if (stack.size() > 1)
			//	throw new IllegalStateException("You are trying to close the root session before all transactions have been unwound.");

			// The order of these three operations is significant

			ofy.flush();

			PendingFutures.completeAllPendingFutures();

			stack.removeLast();
		};
	}

	/** Pushes new context onto stack when a transaction starts. For internal housekeeping only. */
	public void push(final Objectify ofy) {
		stacks.get().add(ofy);
	}

	/** Pops context off of stack after a transaction completes. For internal housekeeping only. */
	public void pop(final Objectify ofy) {
		final Objectify popped = stacks.get().removeLast();
		assert popped == ofy : "Mismatched objectify instances; somehow the stack was corrupted";
	}
}