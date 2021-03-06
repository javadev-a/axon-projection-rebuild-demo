package org.demo.shared;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.axonframework.common.ReflectionUtils;
import org.axonframework.common.transaction.TransactionManager;
import org.axonframework.eventhandling.EventHandlerInvoker;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.Segment;
import org.axonframework.eventhandling.TrackedEventMessage;
import org.axonframework.eventhandling.TrackingEventProcessor;
import org.axonframework.eventhandling.tokenstore.TokenStore;
import org.axonframework.eventsourcing.eventstore.GapAwareTrackingToken;
import org.axonframework.eventsourcing.eventstore.TrackedEventData;
import org.axonframework.messaging.StreamableMessageSource;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.monitoring.MessageMonitor;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
public class ProgressTrackingEventProcessor extends TrackingEventProcessor {
	private final TrackingJdbcEventStorageEngine trackingJdbcEventStorageEngine;
	private volatile Instant lastEventTimestamp;
	private volatile UnitOfWork lastUnitOfWork;

	public ProgressTrackingEventProcessor(String name, EventHandlerInvoker eventHandlerInvoker,
										  TrackingJdbcEventStorageEngine trackingJdbcEventStorageEngine,
										  StreamableMessageSource<TrackedEventMessage<?>> messageSource, TokenStore tokenStore,
										  TransactionManager transactionManager,
										  MessageMonitor<? super EventMessage<?>> messageMonitor) {
		super(name, eventHandlerInvoker, messageSource, tokenStore, transactionManager, messageMonitor);
		this.trackingJdbcEventStorageEngine = trackingJdbcEventStorageEngine;
	}

	@Override
	public void start() {
		log.info(String.format("starting tracking processor [%s]", getName()));
		super.start();
	}

	@Override
	protected void processInUnitOfWork(List<? extends EventMessage<?>> eventMessages,
									   UnitOfWork<? extends EventMessage<?>> unitOfWork,
									   Segment segment) throws Exception {
		super.processInUnitOfWork(eventMessages, unitOfWork, segment);

		if (eventMessages.size() > 0) {
			lastEventTimestamp = eventMessages.get(0).getTimestamp();
			lastUnitOfWork = unitOfWork;

			Status status = getStatus();

			log.info(String.format("processing %d messages [%s], %d%%, currently %d millis behind", eventMessages.size(), getName(),
					status.getProgress(), status.getTimeBehindInMillis()));
		}
	}

	public Status getStatus() {
		try {
			GapAwareTrackingToken lastToken = getLastToken(lastUnitOfWork);

			if (lastToken == null) {
				return new Status(true, 100, 0);
			}

			TrackedEventData latestTrackedEvent = trackingJdbcEventStorageEngine.getLatestTrackedEvent();
			GapAwareTrackingToken latestTrackingToken = (GapAwareTrackingToken) latestTrackedEvent.trackingToken();

			long timeBehindInMillis = Duration.between(lastEventTimestamp, latestTrackedEvent.getTimestamp()).toMillis();
			long progress = (lastToken.getIndex() * 100) / latestTrackingToken.getIndex();

			return new Status(lastToken.getIndex() >= latestTrackingToken.getIndex(), progress, timeBehindInMillis);
		} catch (NoSuchFieldException | SQLException e) {
			log.error(e.toString());
		}

		return new Status(false, 0, Integer.MAX_VALUE);
	}

	private GapAwareTrackingToken getLastToken(UnitOfWork unitOfWork) throws NoSuchFieldException {
		String lastTokenResourceKey = ReflectionUtils.getFieldValue(TrackingEventProcessor.class.getDeclaredField("lastTokenResourceKey"), this);
		return (GapAwareTrackingToken) unitOfWork.getResource(lastTokenResourceKey);
	}

	@Value
	public class Status {
		boolean ready;
		long progress;
		long timeBehindInMillis;
	}
}
