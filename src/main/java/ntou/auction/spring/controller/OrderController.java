package ntou.auction.spring.controller;

import jakarta.validation.Valid;
import ntou.auction.spring.data.entity.*;
import ntou.auction.spring.data.service.*;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping(value = "/api/v1/order", produces = MediaType.APPLICATION_JSON_VALUE)
@CrossOrigin(origins = "http://localhost:3000")
public class OrderController {
    private final OrderService orderService;
    private final ProductService productService;

    private final ShoppingcartService shoppingcartService;
    private final UserService userService;

    private final UserIdentity userIdentity;

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Map<String, String> successMessage = Collections.singletonMap("message", "成功");
    private static final Map<String, String> failMessage = Collections.singletonMap("message", "操作失敗");

    private static final Map<String, String> tooManySellerMessage = Collections.singletonMap("message", "訂單的賣家只能來自同一位");

    private static final Map<String, String> orderNotFound = Collections.singletonMap("message", "訂單不存在");

    private static final Map<String, String> statusError = Collections.singletonMap("message", "該狀態下無法進行操作");

    private static final Map<String, String> identityError = Collections.singletonMap("message", "該狀身分下無法進行操作");

    private static final Map<String, String> formatError = Collections.singletonMap("message", "格式錯誤");

    private static final Map<String, String> notFoundInShoppingCartError = Collections.singletonMap("message", "商品不在購物車中或購買數量過多");

    public OrderController(OrderService orderService, ProductService productService, ShoppingcartService shoppingcartService, UserService userService, UserIdentity userIdentity) {
        this.orderService = orderService;
        this.productService = productService;
        this.shoppingcartService = shoppingcartService;
        this.userService = userService;
        this.userIdentity = userIdentity;
    }

    @GetMapping("/order/all")
    List<OrderWithProductDetail> getAllByBuyer() {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        List<Order> getOrder = orderService.findAllByBuyerId(userId);
        return orderService.orderToOrderWithProductDetail(getOrder);
    }

    @GetMapping("/order/reject")
    List<OrderWithProductDetail> getRejectByBuyer() {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        return orderService.orderToOrderWithProductDetail(orderService.findRejectByBuyerId(userId));
    }

    @GetMapping("/order/waiting")
    List<OrderWithProductDetail> getWaitingByBuyer() {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        return orderService.orderToOrderWithProductDetail(orderService.findWaitingByBuyerId(userId));
    }

    @GetMapping("/order/submit")
    List<OrderWithProductDetail> getSubmitByBuyer() {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        return orderService.orderToOrderWithProductDetail(orderService.findSubmittedByBuyerId(userId));
    }

    @GetMapping("/check")
    List<OrderWithProductDetail> getWaitingBySeller() {
        // filter Waited order with seller
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        return orderService.orderToOrderWithProductDetail(orderService.findWaitingBySellerId(userId));
    }

    @PostMapping("/create")
    ResponseEntity<Map<String, String>> addOrder(@Valid @RequestBody AddOrderRequest request) {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        List<List<Long>> getrequest = request.getProductList();
        // check -> -1: format error, 0: false, 1: true
        Long check = shoppingcartService.checkIsProductAllInShoppingCart(getrequest, userId);
        if(check.equals(-1L)) return ResponseEntity.badRequest().body(formatError);
        if(check.equals(0L)) return ResponseEntity.badRequest().body(notFoundInShoppingCartError);

        // order status -> 0: reject, 1: waiting for submit, 2: submitted but not paid, 3: order done
        Order order = new Order();
        order.setBuyerid(userId);
        order.setUpdateTime(LocalDateTime.parse(LocalDateTime.now().format(formatter), formatter));
        order.setStatus(1L);

        for (List<Long> eachProductAddAmount : getrequest) {
            Long productId = eachProductAddAmount.get(0);
            Long amount = eachProductAddAmount.get(1);
            Product getProduct = productService.getID(productId);
            // Id error
            if (getProduct == null) {
                Map<String, String> ErrorIdMessage = Collections.singletonMap("message", "商品(ID:" + productId + ")不存在");
                return ResponseEntity.badRequest().body(ErrorIdMessage);
            }
            // amount exceed
            if (amount > getProduct.getProductAmount()) {
                Map<String, String> amountExceedReturn = Collections.singletonMap("message", "商品數量(" + getProduct.getProductName() + ")過多");
                return ResponseEntity.badRequest().body(amountExceedReturn);
            }
            order.setSellerid(getProduct.getSellerID());
            List<Long> input = new ArrayList<>();
            input.add(productId);
            input.add(amount);
            order.addProductAddAmount(input);
            // decrease product's amount by amount
            productService.productAmountDecrease(productId, amount);
        }
        // delete Product amount in Shopping cart
        for (List<Long> eachProductAddAmount : getrequest) {
            shoppingcartService.decreaseProductByUserId(userId, eachProductAddAmount.get(0), eachProductAddAmount.get(1));
        }
        boolean result = orderService.addOrder(order);
        if (!result) return ResponseEntity.badRequest().body(tooManySellerMessage);
        return ResponseEntity.ok(successMessage);
    }

    @PostMapping("/makesubmit")
    ResponseEntity<Map<String, String>> makeSubmit(@Valid @RequestBody OperateOrderRequest request) {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        Long orderId = request.getOrderId();
        if (orderId == null) return ResponseEntity.badRequest().body(failMessage);
        // result -> 0: orderNotFound, 1: statusError, 2: idError, 3: success
        Long result = orderService.submitOrder(orderId, userId);
        if (result.equals(0L)) return ResponseEntity.badRequest().body(orderNotFound);
        if (result.equals(1L)) return ResponseEntity.badRequest().body(statusError);
        if (result.equals(2L)) return ResponseEntity.badRequest().body(identityError);
        return ResponseEntity.ok(successMessage);
    }

    @PostMapping("/makedone")
    ResponseEntity<Map<String, String>> makeDone(@Valid @RequestBody OperateOrderRequest request) {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        Long orderId = request.getOrderId();
        if (orderId == null) return ResponseEntity.badRequest().body(failMessage);
        // result -> 0: orderNotFound, 1: statusError, 2: idError, 3: success
        Long result = orderService.doneOrder(orderId, userId);
        if (result.equals(0L)) return ResponseEntity.badRequest().body(orderNotFound);
        if (result.equals(1L)) return ResponseEntity.badRequest().body(statusError);
        if (result.equals(2L)) return ResponseEntity.badRequest().body(identityError);
        return ResponseEntity.ok(successMessage);
    }

    @PostMapping("/makereject")
    ResponseEntity<Map<String, String>> makeReject(@Valid @RequestBody OperateOrderRequest request) {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        Long orderId = request.getOrderId();
        if (orderId == null) return ResponseEntity.badRequest().body(failMessage);
        // 0: orderNotFound, 1: statusError, 2: idError, 3: success
        Long result = orderService.rejectOrder(orderId, userId);
        if (result.equals(0L)) return ResponseEntity.badRequest().body(orderNotFound);
        if (result.equals(1L)) return ResponseEntity.badRequest().body(statusError);
        if (result.equals(2L)) return ResponseEntity.badRequest().body(identityError);
        boolean check = orderService.addAmountToProduct(orderService.findOrderById(orderId));
        if (!check) return ResponseEntity.badRequest().body(orderNotFound); //this may not be happened
        return ResponseEntity.ok(successMessage);
    }

    @PostMapping("/makecancel")
    ResponseEntity<Map<String, String>> makeCancel(@Valid @RequestBody OperateOrderRequest request) {
        Long userId = userService.findByUsername(userIdentity.getUsername()).getId();
        Long orderId = request.getOrderId();
        if (orderId == null) return ResponseEntity.badRequest().body(failMessage);
        // 0: orderNotFound, 1: statusError, 2: idError, 3: success, -1: expired
        Long result = orderService.cancelOrder(orderId, userId);
        if (result.equals(0L)) return ResponseEntity.badRequest().body(orderNotFound);
        if (result.equals(1L)) return ResponseEntity.badRequest().body(statusError);
        if (result.equals(2L)) return ResponseEntity.badRequest().body(identityError);
        Map<String, String> expiredError = Collections.singletonMap("message", "超過7天無法取消訂單");
        if (result.equals(-1L)) return ResponseEntity.badRequest().body(expiredError);
        Order thisOrder = orderService.findOrderById(orderId);
        boolean check = orderService.addAmountToProduct(thisOrder);
        if(!check) return ResponseEntity.badRequest().body(orderNotFound); // this may not be happened
        return ResponseEntity.ok(successMessage);
    }
}
